/*
 * Copyright (C) 2010 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.common.persistence.engine.gae;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.logging.LogFactory;
import org.opendatakit.common.persistence.ITaskLockType;
import org.opendatakit.common.persistence.PersistConsts;
import org.opendatakit.common.persistence.TaskLock;
import org.opendatakit.common.persistence.engine.DatastoreAccessMetrics;
import org.opendatakit.common.persistence.exception.ODKTaskLockException;

import com.google.appengine.api.datastore.DatastoreAttributes.DatastoreType;
import com.google.appengine.api.datastore.DatastoreFailureException;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.PreparedQuery.TooManyResultsException;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Transaction;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheService.IdentifiableValue;
import com.google.appengine.api.memcache.MemcacheService.SetPolicy;

/**
 *
 * @author wbrunette@gmail.com
 * @author mitchellsundt@gmail.com
 *
 */
public class TaskLockImpl implements TaskLock {

  private static final String NO_TRANSACTION_ACTIVE = "Transaction was no longer active";
  private static final String MULTIPLE_RESULTS_ERROR = "Too close to call: Two locks were created within the settle time of each other";
  private static final String MULTIPLE_MEMCACHE_RESULTS_ERROR = "Memcache: Collision of lock timestamps";
  private static final String HOT_MEMCACHE_ENTRY_ERROR = "Memcache: Unable to record lock due to contention";
  private static final String OTHER_ERROR = "Datastore or other failure";
  private static final String ENTITY_GROUP_KIND = "TASK_LOCK_GROUP";
  private static final String ENTITY_GROUP_KEY = "TASK_LOCKS";
  private static final String KIND = "TASK_LOCK";
  private static final String LOCK_ID_PROPERTY = "LOCK_ID";
  private static final String FORM_ID_PROPERTY = "FORM_ID";
  private static final String TASK_TYPE_PROPERTY = "TASK_TYPE";
  private static final String TIMESTAMP_PROPERTY = "TIMESTAMP";

  private final DatastoreAccessMetrics dam;
  private final MemcacheService syncCache;
  private final DatastoreService ds;

  public TaskLockImpl(DatastoreAccessMetrics dam, MemcacheService syncCache) {
    this.dam = dam;
    this.syncCache = syncCache;
    ds = DatastoreServiceFactory.getDatastoreService();
  }

  private void deleteLock(String lockId, String formId, ITaskLockType taskType) {
    try {
      Key entityGroupKey = KeyFactory.createKey(ENTITY_GROUP_KIND, ENTITY_GROUP_KEY);
      Query query = new Query(KIND, entityGroupKey);
      query.setAncestor(entityGroupKey);
      query.setFilter(
        new Query.CompositeFilter( CompositeFilterOperator.AND, Arrays.<Filter>asList(
            new Query.FilterPredicate( FORM_ID_PROPERTY, Query.FilterOperator.EQUAL, formId ),
            new Query.FilterPredicate( TASK_TYPE_PROPERTY, Query.FilterOperator.EQUAL, taskType.getName() )
            )));
      PreparedQuery pquery = ds.prepare(query);

      Iterable<Entity> entities = pquery.asIterable();
      List<Key> keysToDelete = new ArrayList<Key>();
      int recCount = 0;
      for (Entity entity : entities) {
        ++recCount;
        boolean shouldDelete = false;
        // see if deadline is more than a day in the past.
        // if so, remove lock from table.
        Long timestamp = getTimestamp(entity);
        if (timestamp + 24L * 3600L * 1000L < System.currentTimeMillis()) {
          shouldDelete = true;
        }
        // see if lock id matches that of the one supplied.
        // if so, remove lock from table.
        String retrievedLockId = getLockId(entity);
        if (lockId.equals(retrievedLockId)) {
          shouldDelete = true;
        }
        if (shouldDelete) {
          keysToDelete.add(entity.getKey());
        }
      }
      dam.recordQueryUsage(KIND, recCount);

      // we have the list of candidate records
      // now gain a transactional lock for each and
      // delete it.
      for (Key key : keysToDelete) {
        // The lock state in the db is bad, so delete bad locks
        boolean deleteResult = false;
        dam.recordDeleteUsage(KIND);
        Transaction deleteTransaction = ds.beginTransaction();
        try {
          ds.delete(deleteTransaction, key);
          deleteResult = true;
        } catch (Exception e1) {
          e1.printStackTrace();
          deleteResult = false;
        } finally {
          if (deleteResult) {
            deleteTransaction.commit();
          } else {
            deleteTransaction.rollback();
            System.out.println("Rollback deleteLock : " + lockId + " " + formId + " "
                + taskType.getName());
            // if we fail, sleep, since there must be another server in contention
            Thread.sleep(PersistConsts.MAX_SETTLE_MILLISECONDS);
          }
        }
      }
    } catch (Exception e) { // primarily datastore exceptions
      e.printStackTrace();
    } finally {
	  deleteLockIdMemCache(lockId, formId, taskType);
    }
  }

  @Override
  public boolean obtainLock(String lockId, String formId, ITaskLockType taskType) {
    boolean result = false;

    try {
      Transaction transaction = ds.beginTransaction();

      try {
        Entity gaeEntity = queryForLock(formId, taskType);
        System.out.println("Trying to get lock : " + lockId + " " + formId + " "
            + taskType.getName());
        if (gaeEntity == null) {
          Key entityGroupKey = KeyFactory.createKey(ENTITY_GROUP_KIND, ENTITY_GROUP_KEY);
          gaeEntity = new Entity(KIND, entityGroupKey);
          updateValuesNpersist(transaction, lockId, formId, taskType, gaeEntity);
          result = true;
        } else {
          // see if the lock is ours (may be slow appearing due to GAE delays)
          String retrievedLockId = getLockId(gaeEntity);
          result = lockId.equals(retrievedLockId);
        }
        // else you did not get the lock
      } catch (ODKTaskLockException e) {
        result = false;
        e.printStackTrace();
      } finally {
        if (result) {
          transaction.commit();
        } else {
          transaction.rollback();
          System.out.println("Rollback obtainLock : " + lockId + " " + formId + " "
              + taskType.getName());
          // if we fail, sleep, since there must be another server in contention
          Thread.sleep(PersistConsts.MAX_SETTLE_MILLISECONDS);
          return result;
        }
      }
    } catch (DatastoreFailureException e) {
      e.printStackTrace();
      return false;
    } catch (Exception e) { // might be a ConcurrentModificationException ...
      System.out.println("UNEXPECTED EXCEPTION " + e.toString());
      e.printStackTrace();
      return false;
    }

    // and outside the transaction, double-check that we hold the lock
    try {
      // rely on strong consistency guarantee on a High Replication datastore.
      // For Master-Slave, we cannot do that. Must wait for data to settle.
      if ( ds.getDatastoreAttributes().getDatastoreType() != DatastoreType.HIGH_REPLICATION ) {
        // sleep a little to let GAE datastore stabilize
        try {
          Thread.sleep(PersistConsts.MIN_SETTLE_MILLISECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      // verify no one else made a lock
      lockVerification(lockId, formId, taskType);
    } catch (ODKTaskLockException e) {
      result = false;
      deleteLock(lockId, formId, taskType);
    }

    return result;
  }

  private Long getTimestamp(Entity entity) {
    if (entity == null) {
      return 0L;
    }
    Object obj = entity.getProperty(TIMESTAMP_PROPERTY);
    if (obj instanceof Long) {
      Long timestamp = (Long) obj;
      return timestamp;
    }
    return 0L;
  }

  private String getLockId(Entity entity) {
    if (entity == null) {
      return "";
    }

    Object value = entity.getProperty(LOCK_ID_PROPERTY);
    if (value instanceof String) {
      String retrievedId =  (String) value;
      return retrievedId;
    }

    return "";
  }

  /**
   *
   * @param entity
   * @return true if expired
   */
  private boolean isExpired(Entity entity) {
    if (entity == null) {
      return true;
    }
    Long timestamp = getTimestamp(entity);
    Long current = System.currentTimeMillis();
    System.out.println("Time left on lock: " + Long.toString(timestamp - current) );
    if (current.compareTo(timestamp) > 0) {
      return true;
    }
    return false;
  }

  public boolean renewLock(String lockId, String formId, ITaskLockType taskType) {
    boolean result = false;
    try {
      Transaction transaction = ds.beginTransaction();
      try {
        Entity gaeEntity = queryForLock(formId, taskType);
        if (gaeEntity != null) {
          String retrievedLockId = getLockId(gaeEntity);
          if (retrievedLockId.equals(lockId)) {
            updateValuesNpersist(transaction, lockId, formId, taskType, gaeEntity);
            result = true;
          }
        }
        // else you did not get find and update your lock
      } catch (ODKTaskLockException e) {
        result = false;
        e.printStackTrace();
      } finally {
        if (result) {
          transaction.commit();
        } else {
          transaction.rollback();
          System.out.println("Rollback renewLock : " + lockId + " " + formId + " "
              + taskType.getName());
          // if we fail, sleep, since there must be another server in contention
          Thread.sleep(PersistConsts.MAX_SETTLE_MILLISECONDS);
          return result;
        }
      }
    } catch (DatastoreFailureException e) {
      e.printStackTrace();
      return false;
    } catch (Exception e) { // might be a ConcurrentModificationException ...
      System.out.println("UNEXPECTED EXCEPTION " + e.toString());
      e.printStackTrace();
      return false;
    }

    // and outside the transaction, double-check that we hold the lock
    try {
      // rely on strong consistency guarantee on a High Replication datastore.
      // For Master-Slave, we cannot do that. Must wait for data to settle.
      if ( ds.getDatastoreAttributes().getDatastoreType() != DatastoreType.HIGH_REPLICATION ) {
        // sleep a little to let GAE datastore stabilize
        try {
          Thread.sleep(PersistConsts.MIN_SETTLE_MILLISECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      // verify no one else made a lock
      lockVerification(lockId, formId, taskType);
    } catch (ODKTaskLockException e) {
      result = false;
      deleteLock(lockId, formId, taskType);
    }
    return result;
  }

  public boolean releaseLock(String lockId, String formId, ITaskLockType taskType)
      throws ODKTaskLockException {
    System.out.println("Releasing lock : " + lockId + " " + formId + " " + taskType.getName());
    boolean result = false;
	try {
		Transaction transaction = ds.beginTransaction();
		try {
		  Entity gaeEntity = queryForLock(formId, taskType);
		  if (gaeEntity == null) {
			// might have been deleted in an earlier sweep...
			result = true;
		  } else {
			String retrievedLockId = getLockId(gaeEntity);
			if (retrievedLockId.equals(lockId)) {
			  dam.recordDeleteUsage(KIND);
			  ds.delete(transaction, gaeEntity.getKey());
			  result = true;
			}
		  }
		} catch (ODKTaskLockException e) {
		  throw e;
		} catch (Exception e) { // catches datastore issues...
		  e.printStackTrace();
		  throw new ODKTaskLockException(OTHER_ERROR, e);
		} finally {
		  if (result) {
			try {
			  transaction.commit();
			} catch (DatastoreFailureException e) {
			  throw new ODKTaskLockException(OTHER_ERROR, e);
			} catch (Exception e) { // might be a ConcurrentModificationException ...
			  System.out.println("UNEXPECTED EXCEPTION " + e.toString());
			  e.printStackTrace();
			  throw new ODKTaskLockException(OTHER_ERROR, e);
			}
		  } else {
			try {
			  transaction.rollback();
			  System.out.println("Rollback releaseLock : " + lockId + " " + formId + " " + taskType.getName());
			} catch (DatastoreFailureException e) {
			  throw new ODKTaskLockException(OTHER_ERROR, e);
			} catch (Exception e) { // might be a ConcurrentModificationException ...
			  System.out.println("UNEXPECTED EXCEPTION " + e.toString());
			  e.printStackTrace();
			  throw new ODKTaskLockException(OTHER_ERROR, e);
			}
		  }
		}
	} finally {
		deleteLockIdMemCache(lockId, formId, taskType);
	}
	if ( !result ) {
	  // if there was contention and the other party hasn't removed its lock
	  // yet, then our queryForLock() will fail.  Call delete, which has
	  // less restrictive logic than queryForLock().
	  deleteLock(lockId, formId, taskType);
	  System.out.println("releaseLock -- FALLBACK: deleteLock : " + lockId + " " + formId + " " + taskType.getName());
	}
    return result;
  }

  private void lockVerification(String lockId, String formId, ITaskLockType taskType)
      throws ODKTaskLockException {
    Entity verificationEntity = queryForLock(formId, taskType);
    if (verificationEntity == null) {
      throw new ODKTaskLockException("UNABLE TO LOCATE LOCK: " + lockId + " For: " + formId
          + " Task: " + taskType.getName());
    }
    String retrievedLockId = getLockId(verificationEntity);
    if (!lockId.equals(retrievedLockId)) {
      throw new ODKTaskLockException("SOMEONE OVERWROTE THE LOCK" + " Actual: " + retrievedLockId
          + " Expected: " + lockId);
    }
    String retrievedLockIdMemCache = queryForLockIdMemCache(formId, taskType);
    // if it is null, that is OK -- we might have a contention failure or memcache might be down
    if ( retrievedLockIdMemCache != null && !lockId.equals(retrievedLockIdMemCache) ) {
      LogFactory.getLog(TaskLockImpl.class).error("MemCache: lock verification failure. Actual: " + retrievedLockIdMemCache
          + " Expected: " + lockId);

      throw new ODKTaskLockException("MemCache: Undetected Datastore Overwriting of Lock" + " Actual: " + retrievedLockIdMemCache
          + " Expected: " + lockId);
    }
  }

  private void updateValuesNpersist(Transaction transaction, String lockId, String formId,
      ITaskLockType taskType, Entity gaeEntity) throws ODKTaskLockException {
    System.out.println("Persisting lock : " + lockId + " " + formId + " " + taskType.getName());

    dam.recordPutUsage(KIND);
    try {
      Long timestamp = System.currentTimeMillis() + taskType.getLockExpirationTimeout();
      gaeEntity.setProperty(TIMESTAMP_PROPERTY, timestamp);
      gaeEntity.setProperty(LOCK_ID_PROPERTY, lockId);
      gaeEntity.setProperty(FORM_ID_PROPERTY, formId);
      gaeEntity.setProperty(TASK_TYPE_PROPERTY, taskType.getName());
      try {
        updateLockIdTimestampMemCache(lockId, formId, taskType, timestamp);
      } finally {
        ds.put(transaction, gaeEntity);
      }
    } catch (IllegalStateException e) {
      throw new ODKTaskLockException(NO_TRANSACTION_ACTIVE, e);
    } catch (ODKTaskLockException e) {
      throw e;
    } catch (Exception e) { // catches datastore issues...
      e.printStackTrace();
      throw new ODKTaskLockException(OTHER_ERROR, e);
    }
  }

  /**
   * Update the MemCache for (formId, taskType) to record the given timestamp as the gain-time of the lockId.
   * The lockId with the earliest gain-time wins.
   * 
   * @param lockId
   * @param formId
   * @param taskType
   * @param timestamp
   * @throws ODKTaskLockException
   */
  private synchronized void updateLockIdTimestampMemCache(String lockId, String formId, ITaskLockType taskType, Long timestamp) throws ODKTaskLockException {
    if ( syncCache != null ) {
      int i;
      try {
        String formTask = ((formId == null) ? "" : formId) + "@" + taskType.getName();
        for (i = 0 ; i < 10 ; i++ ) {
          IdentifiableValue v = syncCache.contains(formTask) ? syncCache.getIdentifiable(formTask) : null;
          if ( v == null || v.getValue() == null ) {
            TreeMap<Long,String> tm = new TreeMap<Long,String>();
            tm.put(timestamp, lockId);
            if ( syncCache.put(formTask, tm, null, SetPolicy.ADD_ONLY_IF_NOT_PRESENT) ) {
              break;
            }
          } else {
            TreeMap<Long,String> tmOrig = (TreeMap<Long,String>) v.getValue();
            TreeMap<Long,String> tm = new TreeMap<Long,String>(tmOrig);
            
            // remove any old entries for lockId and any that are very old
            Long currentTimestamp = System.currentTimeMillis();
            Long oldTimestamp;
            do {
              oldTimestamp = null;
              for ( Map.Entry<Long,String> entry : tm.entrySet() ) {
                if ( entry.getKey() + 300000L < currentTimestamp ) {
                  // more than 5 minutes old -- remove it
                  oldTimestamp = entry.getKey();
                  break;
                }
                if ( entry.getValue().equals(lockId) ) {
                  oldTimestamp = entry.getKey();
                  break;
                }
              }
              if ( oldTimestamp != null ) {
                tm.remove(oldTimestamp);
              }
            } while ( oldTimestamp != null );
            
            // update with new timestamp
            if ( tm.put(timestamp, lockId) != null ) {
              // some other thread gained the lock first for this timestamp
              throw new ODKTaskLockException(MULTIPLE_MEMCACHE_RESULTS_ERROR);
            }
            
            // try to update the Memcache with these changes.
            if ( syncCache.putIfUntouched(formTask, v, tm) ) {
              break;
            }
          }
        }
      } catch ( ODKTaskLockException e ) {
        throw e;
      } catch ( Throwable t ) {
        t.printStackTrace();
        throw new ODKTaskLockException(OTHER_ERROR, t);
      }
      
      if ( i == 10 ) {
        // crazy contention
        throw new ODKTaskLockException(HOT_MEMCACHE_ENTRY_ERROR);
      }
    }
  }
  
  /**
   * Remove the given lockId from the (formId, taskType) entry.
   * 
   * @param lockId
   * @param formId
   * @param taskType
   */
  private synchronized void deleteLockIdMemCache(String lockId, String formId, ITaskLockType taskType) {
    if ( syncCache != null ) {
      int i;
      try {
        String formTask = ((formId == null) ? "" : formId) + "@" + taskType.getName();
        for (i = 0 ; i < 10 ; i++ ) {
          
          IdentifiableValue v = syncCache.contains(formTask) ? syncCache.getIdentifiable(formTask) : null;
          if ( v == null || v.getValue() == null ) {
            break;
          } else {
            TreeMap<Long,String> tmOrig = (TreeMap<Long,String>) v.getValue();
            TreeMap<Long,String> tm = new TreeMap<Long,String>(tmOrig);
   
            // remove any old entries for lockId and any that are very old
            Long currentTimestamp = System.currentTimeMillis();
            Long oldTimestamp;
            do {
              oldTimestamp = null;
              for ( Map.Entry<Long,String> entry : tm.entrySet() ) {
                if ( entry.getKey() + 300000L < currentTimestamp ) {
                  // more than 5 minutes old -- remove it
                  oldTimestamp = entry.getKey();
                  break;
                }
                if ( entry.getValue().equals(lockId) ) {
                  oldTimestamp = entry.getKey();
                  break;
                }
              }
              if ( oldTimestamp != null ) {
                tm.remove(oldTimestamp);
              }
            } while ( oldTimestamp != null );
            
            if ( syncCache.putIfUntouched(formTask, v, tm) ) {
              break;
            }
          }
        }
      } catch ( Throwable t ) {
        t.printStackTrace();
        // ignore
      }
      // don't care if we had contention and didn't do anything.
      // This will eventually self-correct.
    }
  }

  /**
   * Find the winning lockId for the lock, according to MemCache.
   * 
   * @param formId
   * @param taskType
   * @return
   * @throws ODKTaskLockException
   */
  private synchronized String queryForLockIdMemCache(String formId, ITaskLockType taskType) throws ODKTaskLockException {
    if ( syncCache != null ) {
      try {
        String formTask = ((formId == null) ? "" : formId)  + "@" + taskType.getName();
        IdentifiableValue v = syncCache.contains(formTask) ? syncCache.getIdentifiable(formTask) : null;
        if ( v == null || v.getValue() == null ) {
          return null;
        } else {
          TreeMap<Long,String> tm = (TreeMap<Long,String>) v.getValue();
          Long currentTimestamp = System.currentTimeMillis();
          for ( Long timestamp : tm.keySet() ) {
            if ( timestamp >= currentTimestamp ) {
              return tm.get(timestamp);
            }
          }
          return null;
        }
      } catch ( Throwable t ) {
        t.printStackTrace();
        // ignore
      }
    }
    return null;
  }
  
  private Entity queryForLock(String formId, ITaskLockType taskType) throws ODKTaskLockException {
    int readCount = 0;
    try {
      Key entityGroupKey = KeyFactory.createKey(ENTITY_GROUP_KIND, ENTITY_GROUP_KEY);
      Query query = new Query(KIND, entityGroupKey);
      query.setAncestor(entityGroupKey);
      query.setFilter(
          new Query.CompositeFilter( CompositeFilterOperator.AND, Arrays.<Filter>asList(
              new Query.FilterPredicate( FORM_ID_PROPERTY, Query.FilterOperator.EQUAL, formId ),
              new Query.FilterPredicate( TASK_TYPE_PROPERTY, Query.FilterOperator.EQUAL, taskType.getName() )
              )));
      PreparedQuery pquery = ds.prepare(query);
      Iterable<Entity> entities = pquery.asIterable();
      // There may be expired locks in the database.
      // Skip over those and find the active lock.
      Entity active = null;
      for (Entity e : entities) {
        ++readCount;
        if (!isExpired(e)) {
          if (active != null) {
            Long timestamp1 = getTimestamp(active);
            Long timestamp2 = getTimestamp(e);
            // can't tell who won if we are within the settle interval.
            if (Math.abs(timestamp1 - timestamp2) < PersistConsts.MIN_SETTLE_MILLISECONDS) {
              throw new ODKTaskLockException(MULTIPLE_RESULTS_ERROR);
            }
            // otherwise, whichever holder held the lock first wins
            int cmp = timestamp1.compareTo(timestamp2);
            if (cmp > 0) {
              active = e;
            }
          } else {
            active = e;
          }
        }
      }
      return active;
    } catch (ODKTaskLockException e) {
      throw e;
    } catch (TooManyResultsException e) {
      throw new ODKTaskLockException(MULTIPLE_RESULTS_ERROR, e);
    } catch (IllegalStateException e) {
      throw new ODKTaskLockException(NO_TRANSACTION_ACTIVE, e);
    } catch (Exception e) { // may catch datastore issues?
      e.printStackTrace();
      throw new ODKTaskLockException(OTHER_ERROR, e);
    } finally {
      dam.recordQueryUsage(KIND, readCount);
    }

  }

}
