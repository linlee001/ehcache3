/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.clustered.server;

import org.ehcache.clustered.common.Consistency;
import org.ehcache.clustered.common.PoolAllocation;
import org.ehcache.clustered.common.internal.ClusteredEhcacheIdentity;
import org.ehcache.clustered.common.internal.ServerStoreConfiguration;
import org.ehcache.clustered.common.internal.exceptions.ClusterException;
import org.ehcache.clustered.common.internal.exceptions.IllegalMessageException;
import org.ehcache.clustered.common.internal.exceptions.LifecycleException;
import org.ehcache.clustered.common.internal.exceptions.ServerMisconfigurationException;
import org.ehcache.clustered.common.internal.messages.EhcacheEntityMessage;
import org.ehcache.clustered.common.internal.messages.EhcacheEntityResponse;
import org.ehcache.clustered.common.internal.messages.EhcacheMessageType;
import org.ehcache.clustered.common.internal.messages.EhcacheOperationMessage;
import org.ehcache.clustered.common.internal.messages.LifecycleMessage;
import org.ehcache.clustered.common.internal.messages.LifecycleMessage.ConfigureStoreManager;
import org.ehcache.clustered.server.internal.messages.PassiveReplicationMessage;
import org.ehcache.clustered.server.internal.messages.PassiveReplicationMessage.ChainReplicationMessage;
import org.ehcache.clustered.server.internal.messages.PassiveReplicationMessage.ClearInvalidationCompleteMessage;
import org.ehcache.clustered.server.internal.messages.PassiveReplicationMessage.InvalidationCompleteMessage;
import org.ehcache.clustered.common.internal.messages.ServerStoreOpMessage;
import org.ehcache.clustered.common.internal.messages.StateRepositoryOpMessage;
import org.ehcache.clustered.server.internal.messages.EhcacheDataSyncMessage;
import org.ehcache.clustered.server.internal.messages.EhcacheStateSyncMessage;
import org.ehcache.clustered.server.internal.messages.EhcacheSyncMessage;
import org.ehcache.clustered.server.management.Management;
import org.ehcache.clustered.server.state.ClientMessageTracker;
import org.ehcache.clustered.server.state.EhcacheStateService;
import org.ehcache.clustered.server.state.InvalidationTracker;
import org.ehcache.clustered.server.state.config.EhcacheStateServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.entity.BasicServiceConfiguration;
import org.terracotta.entity.PassiveServerEntity;
import org.terracotta.entity.ServiceRegistry;
import org.terracotta.offheapresource.OffHeapResources;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.ehcache.clustered.common.internal.messages.EhcacheMessageType.isLifecycleMessage;
import static org.ehcache.clustered.common.internal.messages.EhcacheMessageType.isPassiveReplicationMessage;
import static org.ehcache.clustered.common.internal.messages.EhcacheMessageType.isStateRepoOperationMessage;
import static org.ehcache.clustered.common.internal.messages.EhcacheMessageType.isStoreOperationMessage;

class EhcachePassiveEntity implements PassiveServerEntity<EhcacheEntityMessage, EhcacheEntityResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EhcachePassiveEntity.class);

  private final UUID identity;
  private final Set<String> offHeapResourceIdentifiers;
  private final EhcacheStateService ehcacheStateService;
  private final Management management;

  @Override
  public void invoke(EhcacheEntityMessage message) {

    try {
      if (this.offHeapResourceIdentifiers.isEmpty()) {
        throw new ServerMisconfigurationException("Server started without any offheap resources defined." +
                                                  " Check your server configuration and define at least one offheap resource.");
      }

      if (message instanceof EhcacheOperationMessage) {
        EhcacheOperationMessage operationMessage = (EhcacheOperationMessage) message;
        EhcacheMessageType messageType = operationMessage.getMessageType();
        if (isStoreOperationMessage(messageType)) {
          invokeServerStoreOperation((ServerStoreOpMessage)message);
        } else if (isLifecycleMessage(messageType)) {
          invokeLifeCycleOperation((LifecycleMessage) message);
        } else if (isStateRepoOperationMessage(messageType)) {
          ehcacheStateService.getStateRepositoryManager().invoke((StateRepositoryOpMessage)message);
        } else if (isPassiveReplicationMessage(messageType)) {
          invokeRetirementMessages((PassiveReplicationMessage)message);
        }
      } else if (message instanceof EhcacheSyncMessage) {
        invokeSyncOperation((EhcacheSyncMessage) message);
      }

      throw new IllegalMessageException("Unknown message : " + message);
    } catch (Exception e) {
      LOGGER.error("Unexpected exception raised during operation: " + message, e);
    }

  }

  EhcachePassiveEntity(ServiceRegistry services, byte[] config, final KeySegmentMapper mapper) {
    this.identity = ClusteredEhcacheIdentity.deserialize(config);
    OffHeapResources offHeapResources = services.getService(new BasicServiceConfiguration<OffHeapResources>(OffHeapResources.class));
    if (offHeapResources == null) {
      this.offHeapResourceIdentifiers = Collections.emptySet();
    } else {
      this.offHeapResourceIdentifiers = offHeapResources.getAllIdentifiers();
    }
    ehcacheStateService = services.getService(new EhcacheStateServiceConfig(services, this.offHeapResourceIdentifiers, mapper));
    if (ehcacheStateService == null) {
      throw new AssertionError("Server failed to retrieve EhcacheStateService.");
    }
    management = new Management(services, ehcacheStateService);
  }

  private void invokeRetirementMessages(PassiveReplicationMessage message) throws ClusterException {

    switch (message.getMessageType()) {
      case CHAIN_REPLICATION_OP:
        LOGGER.debug("Chain Replication message for msgId {} & client Id {}", message.getId(), message.getClientId());
        ChainReplicationMessage retirementMessage = (ChainReplicationMessage)message;
        ServerStoreImpl cacheStore = ehcacheStateService.getStore(retirementMessage.getCacheId());
        if (cacheStore == null) {
          // An operation on a non-existent store should never get out of the client
          throw new LifecycleException("Clustered tier does not exist : '" + retirementMessage.getCacheId() + "'");
        }
        cacheStore.put(retirementMessage.getKey(), retirementMessage.getChain());
        ehcacheStateService.getClientMessageTracker().applied(message.getId(), message.getClientId());
        trackHashInvalidationForEventualCache(retirementMessage);
        break;
      case CLIENT_ID_TRACK_OP:
        LOGGER.debug("PassiveReplicationMessage message for msgId {} & client Id {}", message.getId(), message.getClientId());
        ehcacheStateService.getClientMessageTracker().add(message.getClientId());
        break;
      case INVALIDATION_COMPLETE:
        untrackHashInvalidationForEventualCache((InvalidationCompleteMessage)message);
        break;
      case CLEAR_INVALIDATION_COMPLETE:
        ehcacheStateService.getInvalidationTracker(((ClearInvalidationCompleteMessage)message).getCacheId()).setClearInProgress(false);
        break;
      case CREATE_SERVER_STORE_REPLICATION:
        ehcacheStateService.getClientMessageTracker().applied(message.getId(), message.getClientId());
        PassiveReplicationMessage.CreateServerStoreReplicationMessage createMessage = (PassiveReplicationMessage.CreateServerStoreReplicationMessage) message;
        createServerStore(createMessage.getStoreName(), createMessage.getStoreConfiguration());
        break;
      case DESTROY_SERVER_STORE_REPLICATION:
        ehcacheStateService.getClientMessageTracker().applied(message.getId(), message.getClientId());
        PassiveReplicationMessage.DestroyServerStoreReplicationMessage destroyMessage = (PassiveReplicationMessage.DestroyServerStoreReplicationMessage) message;
        destroyServerStore(destroyMessage.getStoreName());
        break;
      default:
        throw new IllegalMessageException("Unknown Retirement Message : " + message);
    }
  }

  private void untrackHashInvalidationForEventualCache(InvalidationCompleteMessage message) {
    InvalidationCompleteMessage invalidationCompleteMessage = message;
    ehcacheStateService.getInvalidationTracker(invalidationCompleteMessage.getCacheId()).getInvalidationMap().computeIfPresent(invalidationCompleteMessage.getKey(), (key, count) -> {
      if (count == 1) {
        return null;
      }
      return count--;
    });
  }

  private void trackHashInvalidationForEventualCache(ChainReplicationMessage retirementMessage) {
    InvalidationTracker invalidationTracker = ehcacheStateService.getInvalidationTracker(retirementMessage.getCacheId());
    if (invalidationTracker != null) {
      invalidationTracker.getInvalidationMap().compute(retirementMessage.getKey(), (key, count) -> {
        if (count == null) {
          return 1;
        } else {
          return count++;
        }
      });
    }
  }

  private void invokeServerStoreOperation(ServerStoreOpMessage message) throws ClusterException {
    ServerStoreImpl cacheStore = ehcacheStateService.getStore(message.getCacheId());
    if (cacheStore == null) {
      // An operation on a non-existent store should never get out of the client
      throw new LifecycleException("Clustered tier does not exist : '" + message.getCacheId() + "'");
    }

    switch (message.getMessageType()) {
      case APPEND:
      case GET_AND_APPEND: {
        LOGGER.debug("ServerStore append/getAndAppend message for msgId {} & client Id {} is tracked now.", message.getId(), message.getClientId());
        ehcacheStateService.getClientMessageTracker().track(message.getId(), message.getClientId());
        break;
      }
      case REPLACE: {
        ServerStoreOpMessage.ReplaceAtHeadMessage replaceAtHeadMessage = (ServerStoreOpMessage.ReplaceAtHeadMessage)message;
        cacheStore.replaceAtHead(replaceAtHeadMessage.getKey(), replaceAtHeadMessage.getExpect(), replaceAtHeadMessage.getUpdate());
        break;
      }
      case CLEAR: {
        cacheStore.clear();
        InvalidationTracker invalidationTracker = ehcacheStateService.getInvalidationTracker(message.getCacheId());
        if (invalidationTracker != null) {
          invalidationTracker.setClearInProgress(true);
        }
        break;
      }
      default:
        throw new IllegalMessageException("Unknown ServerStore operation : " + message);
    }
  }

  private void invokeSyncOperation(EhcacheSyncMessage message) throws ClusterException {
    switch (message.getMessageType()) {
      case STATE:
        EhcacheStateSyncMessage stateSyncMessage = (EhcacheStateSyncMessage) message;

        ehcacheStateService.configure(stateSyncMessage.getConfiguration());
        management.sharedPoolsConfigured();

        for (Map.Entry<String, ServerStoreConfiguration> entry : stateSyncMessage.getStoreConfigs().entrySet()) {
          ehcacheStateService.createStore(entry.getKey(), entry.getValue());
          if(entry.getValue().getConsistency() == Consistency.EVENTUAL) {
            ehcacheStateService.addInvalidationtracker(entry.getKey());
          }
          management.serverStoreCreated(entry.getKey());
        }
        stateSyncMessage.getTrackedClients().forEach(id -> ehcacheStateService.getClientMessageTracker().add(id));
        break;
      case DATA:
        EhcacheDataSyncMessage dataSyncMessage = (EhcacheDataSyncMessage) message;
        ehcacheStateService.getStore(dataSyncMessage.getCacheId()).put(dataSyncMessage.getKey(), dataSyncMessage.getChain());
        break;
      default:
        throw new IllegalMessageException("Unknown Sync operation " + message.getMessageType());
    }
  }

  private void invokeLifeCycleOperation(LifecycleMessage message) throws ClusterException {
    switch (message.getMessageType()) {
      case CONFIGURE:
        configure((ConfigureStoreManager) message);
        break;
      case VALIDATE:
        trackAndApplyMessage(message);
        break;
      case CREATE_SERVER_STORE:
      case DESTROY_SERVER_STORE:
        ehcacheStateService.getClientMessageTracker().track(message.getId(), message.getClientId());
        break;
      default:
        throw new IllegalMessageException("Unknown LifeCycle operation " + message);
    }
  }

  private void configure(ConfigureStoreManager message) throws ClusterException {
    ehcacheStateService.configure(message.getConfiguration());
    ehcacheStateService.getClientMessageTracker().setEntityConfiguredStamp(message.getClientId(), message.getId());
    management.sharedPoolsConfigured();
  }

  private void trackAndApplyMessage(LifecycleMessage message) {
    ClientMessageTracker clientMessageTracker = ehcacheStateService.getClientMessageTracker();
    if (!clientMessageTracker.isAdded(message.getClientId())) {
      throw new IllegalStateException("Untracked client id " + message.getClientId());
    }
    clientMessageTracker.track(message.getId(), message.getClientId());
    clientMessageTracker.applied(message.getId(), message.getClientId());
  }

  private void createServerStore(String storeName, ServerStoreConfiguration configuration) throws ClusterException {
    if (!ehcacheStateService.isConfigured()) {
      throw new LifecycleException("Clustered Tier Manager is not configured");
    }
    if(configuration.getPoolAllocation() instanceof PoolAllocation.Unknown) {
      throw new LifecycleException("Clustered tier can't be created with an Unknown resource pool");
    }

    LOGGER.info("Creating new clustered tier '{}'", storeName);

    ehcacheStateService.createStore(storeName, configuration);
    if(configuration.getConsistency() == Consistency.EVENTUAL) {
      ehcacheStateService.addInvalidationtracker(storeName);
    }
    management.serverStoreCreated(storeName);
  }

  private void destroyServerStore(String storeName) throws ClusterException {
    if (!ehcacheStateService.isConfigured()) {
      throw new LifecycleException("Clustered Tier Manager is not configured");
    }

    LOGGER.info("Destroying clustered tier '{}'", storeName);
    management.serverStoreDestroyed(storeName);
    ehcacheStateService.destroyServerStore(storeName);
    ehcacheStateService.removeInvalidationtracker(storeName);
  }

  @Override
  public void startSyncEntity() {
    LOGGER.info("Sync started.");
  }

  @Override
  public void endSyncEntity() {
    LOGGER.info("Sync completed.");
  }

  @Override
  public void startSyncConcurrencyKey(int concurrencyKey) {
    LOGGER.info("Sync started for concurrency key {}.", concurrencyKey);
  }

  @Override
  public void endSyncConcurrencyKey(int concurrencyKey) {
    LOGGER.info("Sync complete for concurrency key {}.", concurrencyKey);
  }

  @Override
  public void createNew() {
    management.init();
  }

  @Override
  public void destroy() {
    management.close();
    ehcacheStateService.destroy();
  }
}
