/*
 * #%L
 * %%
 * Copyright (C) 2020 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.joynr.capabilities;

import static io.joynr.runtime.SystemServicesSettings.PROPERTY_CAPABILITIES_FRESHNESS_UPDATE_INTERVAL_MS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import io.joynr.exceptions.DiscoveryException;
import io.joynr.exceptions.JoynrException;
import io.joynr.exceptions.JoynrMessageNotSentException;
import io.joynr.exceptions.JoynrRuntimeException;
import io.joynr.exceptions.JoynrTimeoutException;
import io.joynr.messaging.ConfigurableMessagingSettings;
import io.joynr.messaging.MessagingPropertyKeys;
import io.joynr.messaging.MessagingQos;
import io.joynr.messaging.routing.RoutingTable;
import io.joynr.messaging.routing.TransportReadyListener;
import io.joynr.provider.DeferredVoid;
import io.joynr.provider.Promise;
import io.joynr.provider.PromiseListener;
import io.joynr.proxy.Callback;
import io.joynr.proxy.CallbackWithModeledError;
import io.joynr.runtime.GlobalAddressProvider;
import io.joynr.runtime.ShutdownNotifier;
import joynr.exceptions.ApplicationException;
import joynr.exceptions.ProviderRuntimeException;
import joynr.infrastructure.GlobalCapabilitiesDirectory;
import joynr.system.RoutingTypes.Address;
import joynr.system.RoutingTypes.MqttAddress;
import joynr.types.DiscoveryEntry;
import joynr.types.DiscoveryEntryWithMetaInfo;
import joynr.types.DiscoveryError;
import joynr.types.DiscoveryQos;
import joynr.types.DiscoveryScope;
import joynr.types.GlobalDiscoveryEntry;
import joynr.types.ProviderScope;

@Singleton
public class LocalCapabilitiesDirectoryImpl extends AbstractLocalCapabilitiesDirectory
        implements TransportReadyListener {

    private static final Logger logger = LoggerFactory.getLogger(LocalCapabilitiesDirectoryImpl.class);

    private static final Set<DiscoveryScope> INCLUDE_LOCAL_SCOPES = new HashSet<>();
    static {
        INCLUDE_LOCAL_SCOPES.add(DiscoveryScope.LOCAL_ONLY);
        INCLUDE_LOCAL_SCOPES.add(DiscoveryScope.LOCAL_AND_GLOBAL);
        INCLUDE_LOCAL_SCOPES.add(DiscoveryScope.LOCAL_THEN_GLOBAL);
    }

    private static final Set<DiscoveryScope> INCLUDE_GLOBAL_SCOPES = new HashSet<>();
    static {
        INCLUDE_GLOBAL_SCOPES.add(DiscoveryScope.GLOBAL_ONLY);
        INCLUDE_GLOBAL_SCOPES.add(DiscoveryScope.LOCAL_AND_GLOBAL);
        INCLUDE_GLOBAL_SCOPES.add(DiscoveryScope.LOCAL_THEN_GLOBAL);
    }

    private static final long READD_INTERVAL_DAYS = 7L;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> freshnessUpdateScheduledFuture;
    private ScheduledFuture<?> reAddAllGlobalEntriesScheduledFuture;

    private DiscoveryEntryStore<DiscoveryEntry> localDiscoveryEntryStore;
    private GlobalCapabilitiesDirectoryClient globalCapabilitiesDirectoryClient;
    private DiscoveryEntryStore<GlobalDiscoveryEntry> globalDiscoveryEntryCache;
    private final Map<String, List<String>> globalProviderParticipantIdToGbidListMap;
    private GcdTaskSequencer gcdTaskSequencer;

    private RoutingTable routingTable;

    private GlobalAddressProvider globalAddressProvider;

    private Address globalAddress;
    private Object globalAddressLock = new Object();

    private List<QueuedDiscoveryEntry> queuedDiscoveryEntries = new ArrayList<QueuedDiscoveryEntry>();

    private final String[] knownGbids;

    // Start up time of the cluster controller
    private final long ccStartUpDateInMs;
    private final long defaultExpiryTimeMs;
    private final long defaultTtlAddAndRemove;

    static class QueuedDiscoveryEntry {
        private DiscoveryEntry discoveryEntry;
        private String[] gbids;
        private Add1Deferred deferred;
        private boolean awaitGlobalRegistration;

        public QueuedDiscoveryEntry(DiscoveryEntry discoveryEntry,
                                    String[] gbids,
                                    Add1Deferred deferred,
                                    boolean awaitGlobalRegistration) {
            this.discoveryEntry = discoveryEntry;
            this.gbids = gbids;
            this.deferred = deferred;
            this.awaitGlobalRegistration = awaitGlobalRegistration;
        }

        public DiscoveryEntry getDiscoveryEntry() {
            return discoveryEntry;
        }

        public String[] getGbids() {
            return gbids;
        }

        public Add1Deferred getDeferred() {
            return deferred;
        }

        public boolean getAwaitGlobalRegistration() {
            return awaitGlobalRegistration;
        }
    }

    @Inject
    // CHECKSTYLE IGNORE ParameterNumber FOR NEXT 1 LINES
    public LocalCapabilitiesDirectoryImpl(CapabilitiesProvisioning capabilitiesProvisioning,
                                          GlobalAddressProvider globalAddressProvider,
                                          DiscoveryEntryStore<DiscoveryEntry> localDiscoveryEntryStore,
                                          DiscoveryEntryStore<GlobalDiscoveryEntry> globalDiscoveryEntryCache,
                                          RoutingTable routingTable,
                                          GlobalCapabilitiesDirectoryClient globalCapabilitiesDirectoryClient,
                                          ExpiredDiscoveryEntryCacheCleaner expiredDiscoveryEntryCacheCleaner,
                                          @Named(PROPERTY_CAPABILITIES_FRESHNESS_UPDATE_INTERVAL_MS) long freshnessUpdateIntervalMs,
                                          @Named(JOYNR_SCHEDULER_CAPABILITIES_FRESHNESS) ScheduledExecutorService freshnessUpdateScheduler,
                                          ShutdownNotifier shutdownNotifier,
                                          @Named(MessagingPropertyKeys.GBID_ARRAY) String[] knownGbids,
                                          @Named(ConfigurableMessagingSettings.PROPERTY_DISCOVERY_PROVIDER_DEFAULT_EXPIRY_TIME_MS) long defaultExpiryTimeMs) {
        // set up current date as the start time of the cluster controller
        this.ccStartUpDateInMs = System.currentTimeMillis();
        globalProviderParticipantIdToGbidListMap = new HashMap<>();
        gcdTaskSequencer = new GcdTaskSequencer();
        this.globalAddressProvider = globalAddressProvider;
        // CHECKSTYLE:ON
        this.routingTable = routingTable;
        this.localDiscoveryEntryStore = localDiscoveryEntryStore;
        this.globalDiscoveryEntryCache = globalDiscoveryEntryCache;
        this.globalCapabilitiesDirectoryClient = globalCapabilitiesDirectoryClient;
        this.knownGbids = knownGbids.clone();
        this.defaultExpiryTimeMs = defaultExpiryTimeMs;
        this.defaultTtlAddAndRemove = MessagingQos.DEFAULT_TTL;
        Collection<GlobalDiscoveryEntry> provisionedDiscoveryEntries = capabilitiesProvisioning.getDiscoveryEntries();
        this.globalDiscoveryEntryCache.add(provisionedDiscoveryEntries);
        for (GlobalDiscoveryEntry provisionedEntry : provisionedDiscoveryEntries) {
            String participantId = provisionedEntry.getParticipantId();
            if (GlobalCapabilitiesDirectory.INTERFACE_NAME.equals(provisionedEntry.getInterfaceName())) {
                mapGbidsToGlobalProviderParticipantId(participantId, knownGbids);
            } else {
                Address address = CapabilityUtils.getAddressFromGlobalDiscoveryEntry(provisionedEntry);
                mapGbidsToGlobalProviderParticipantId(participantId, getGbids(address));
            }
        }
        expiredDiscoveryEntryCacheCleaner.scheduleCleanUpForCaches(new ExpiredDiscoveryEntryCacheCleaner.CleanupAction() {
            @Override
            public void cleanup(Set<DiscoveryEntry> expiredDiscoveryEntries) {
                for (DiscoveryEntry discoveryEntry : expiredDiscoveryEntries) {
                    removeInternal(discoveryEntry.getParticipantId(), discoveryEntry.getQos().getScope());
                }
            }
        }, globalDiscoveryEntryCache, localDiscoveryEntryStore);
        this.scheduler = freshnessUpdateScheduler;
        this.scheduler.schedule(gcdTaskSequencer, 0, TimeUnit.MILLISECONDS);
        setUpPeriodicFreshnessUpdate(freshnessUpdateIntervalMs);
        reAddAllGlobalDiscoveryEntriesPeriodically();
        shutdownNotifier.registerForShutdown(this);
    }

    private String[] getGbids(Address address) {
        String[] gbids;
        if (address instanceof MqttAddress) {
            // For backwards compatibility, the GBID is stored in the brokerUri field of MqttAddress which was not evaluated in older joynr versions
            gbids = new String[]{ ((MqttAddress) address).getBrokerUri() };
        } else {
            // use default GBID for all other address types
            gbids = new String[]{ knownGbids[0] };
        }
        return gbids;
    }

    private void mapGbidsToGlobalProviderParticipantId(String participantId, String[] gbids) {
        List<String> newGbidsList = new ArrayList<String>(Arrays.asList(gbids));
        if (globalProviderParticipantIdToGbidListMap.containsKey(participantId)) {
            List<String> nonDuplicateOldGbids = globalProviderParticipantIdToGbidListMap.get(participantId)
                                                                                        .stream()
                                                                                        .filter(gbid -> !newGbidsList.contains(gbid))
                                                                                        .collect(Collectors.toList());
            newGbidsList.addAll(nonDuplicateOldGbids);
        }
        globalProviderParticipantIdToGbidListMap.put(participantId, newGbidsList);
    }

    private void setUpPeriodicFreshnessUpdate(final long freshnessUpdateIntervalMs) {
        logger.trace("Setting up periodic freshness update with interval {}", freshnessUpdateIntervalMs);
        Runnable command = new Runnable() {
            @Override
            public void run() {
                long lastSeenDateMs = System.currentTimeMillis();
                long expiryDateMs = lastSeenDateMs + defaultExpiryTimeMs;

                // Touches all discovery entries, but only returns the participantIds of global ones 
                String[] participantIds = localDiscoveryEntryStore.touchDiscoveryEntries(lastSeenDateMs, expiryDateMs);
                // update globalDiscoveryEntryCache
                globalDiscoveryEntryCache.touchDiscoveryEntries(participantIds, lastSeenDateMs, expiryDateMs);

                if (participantIds == null || participantIds.length == 0) {
                    logger.debug("touch has not been called, because there are no providers to touch");
                    return;
                }

                final Map<String, List<String>> gbidToParticipantIdsListMap = new HashMap<>();
                for (String gbid : knownGbids) {
                    gbidToParticipantIdsListMap.put(gbid, Collections.emptyList());
                }

                synchronized (globalDiscoveryEntryCache) {
                    for (String participantIdToTouch : participantIds) {
                        List<String> gbids = globalProviderParticipantIdToGbidListMap.get(participantIdToTouch);

                        if (gbids == null || gbids.isEmpty()) {
                            logger.warn("touch cannot be called for provider with participantId {}, no GBID found.",
                                        participantIdToTouch);
                            break;
                        }

                        String gbidToTouch = gbids.get(0);
                        if (!gbidToParticipantIdsListMap.containsKey(gbidToTouch)) {
                            logger.error("touch: found GBID {} for particpantId {} is unknown.",
                                         gbidToTouch,
                                         participantIdToTouch);
                            continue;
                        }
                        List<String> participantIdsToTouch = new ArrayList<String>(gbidToParticipantIdsListMap.get(gbidToTouch));
                        participantIdsToTouch.add(participantIdToTouch);
                        gbidToParticipantIdsListMap.put(gbidToTouch, participantIdsToTouch);
                    }
                }

                for (Map.Entry<String, List<String>> entry : gbidToParticipantIdsListMap.entrySet()) {
                    String gbid = entry.getKey();
                    List<String> participantIdsToTouch = entry.getValue();

                    if (participantIdsToTouch.isEmpty()) {
                        logger.debug("touch(gbid={}) has not been called because there are no providers to touch for it.",
                                     gbid);
                        continue;
                    }

                    Callback<Void> callback = new Callback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            if (logger.isTraceEnabled()) {
                                String participantIdsStr = String.join(",", participantIdsToTouch);
                                logger.trace("touch(participantIds={}, gbid={}) succeeded.", participantIdsStr, gbid);
                            } else {
                                logger.debug("touch(gbid={}) succeeded.", gbid);
                            }
                        }

                        @Override
                        public void onFailure(JoynrRuntimeException error) {
                            String participantIdsStr = String.join(",", participantIdsToTouch);
                            logger.error("touch(participantIds={}, gbid={}) failed: {}",
                                         participantIdsStr,
                                         gbid,
                                         error.toString());
                        }
                    };
                    globalCapabilitiesDirectoryClient.touch(callback,
                                                            participantIdsToTouch.toArray(new String[participantIdsToTouch.size()]),
                                                            gbid);
                }
            }
        };
        freshnessUpdateScheduledFuture = scheduler.scheduleAtFixedRate(command,
                                                                       freshnessUpdateIntervalMs,
                                                                       freshnessUpdateIntervalMs,
                                                                       TimeUnit.MILLISECONDS);
    }

    /**
     * Adds local capability to local and (depending on SCOPE) the global directory
     */
    @Override
    public Promise<DeferredVoid> add(final DiscoveryEntry discoveryEntry) {
        boolean awaitGlobalRegistration = false;
        return add(discoveryEntry, awaitGlobalRegistration);
    }

    @Override
    public Promise<DeferredVoid> add(final DiscoveryEntry discoveryEntry, final Boolean awaitGlobalRegistration) {
        Promise<Add1Deferred> addPromise = add(discoveryEntry, awaitGlobalRegistration, new String[]{});
        DeferredVoid deferredVoid = new DeferredVoid();
        addPromise.then(new PromiseListener() {
            @Override
            public void onRejection(JoynrException exception) {
                if (exception instanceof ApplicationException) {
                    DiscoveryError error = ((ApplicationException) exception).getError();
                    deferredVoid.reject(new ProviderRuntimeException("Error registering provider "
                            + discoveryEntry.getParticipantId() + " in default backend: " + error));
                } else if (exception instanceof ProviderRuntimeException) {
                    deferredVoid.reject((ProviderRuntimeException) exception);
                } else {
                    deferredVoid.reject(new ProviderRuntimeException("Unknown error registering provider "
                            + discoveryEntry.getParticipantId() + " in default backend: " + exception));
                }
            }

            @Override
            public void onFulfillment(Object... values) {
                deferredVoid.resolve();
            }
        });
        return new Promise<>(deferredVoid);
    }

    private DiscoveryError validateGbids(final String[] gbids) {
        if (gbids == null) {
            return DiscoveryError.INVALID_GBID;
        }

        HashSet<String> gbidSet = new HashSet<String>();
        for (String gbid : gbids) {
            if (gbid == null || gbid.isEmpty() || gbidSet.contains(gbid)) {
                return DiscoveryError.INVALID_GBID;
            }
            gbidSet.add(gbid);

            boolean found = false;
            for (String validGbid : knownGbids) {
                if (gbid.equals(validGbid)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return DiscoveryError.UNKNOWN_GBID;
            }
        }
        return null;
    }

    @Override
    public Promise<Add1Deferred> add(DiscoveryEntry discoveryEntry, Boolean awaitGlobalRegistration, String[] gbids) {
        final Add1Deferred deferred = new Add1Deferred();

        DiscoveryError validationResult = validateGbids(gbids);
        if (validationResult != null) {
            deferred.reject(validationResult);
            return new Promise<>(deferred);
        }
        if (gbids.length == 0) {
            // register provider in default backend
            gbids = knownGbids;
        }

        discoveryEntry.setLastSeenDateMs(System.currentTimeMillis());

        synchronized (globalDiscoveryEntryCache) {
            if (localDiscoveryEntryStore.hasDiscoveryEntry(discoveryEntry)) {
                Optional<DiscoveryEntry> optionalDiscoveryEntry = localDiscoveryEntryStore.lookup(discoveryEntry.getParticipantId(),
                                                                                                  Long.MAX_VALUE);
                if (optionalDiscoveryEntry.isPresent() && discoveryEntry.getQos().getScope().equals(ProviderScope.LOCAL)
                        && optionalDiscoveryEntry.get().equals(discoveryEntry)) {
                    // in this case, no further need for global registration is required. Registration completed.
                    deferred.resolve();
                    return new Promise<>(deferred);
                }
            }
            // check for duplicate participantId
            Optional<GlobalDiscoveryEntry> cachedEntry = globalDiscoveryEntryCache.lookup(discoveryEntry.getParticipantId(),
                                                                                          Long.MAX_VALUE);
            if (cachedEntry.isPresent()) {
                logger.warn("Add participantId {} removes cached entry with the same participantId: {}",
                            discoveryEntry.getParticipantId(),
                            cachedEntry.get());
                globalDiscoveryEntryCache.remove(discoveryEntry.getParticipantId());
            }
            if (discoveryEntry.getQos().getScope().equals(ProviderScope.GLOBAL)) {
                mapGbidsToGlobalProviderParticipantId(discoveryEntry.getParticipantId(), gbids);
            }
            localDiscoveryEntryStore.add(discoveryEntry);
        }

        /*
         * In case awaitGlobalRegistration is true, a result for this 'add' call will not be returned before the call to
         * the globalDiscovery has either succeeded, failed or timed out. In case of failure or timeout the already
         * created discoveryEntry will also be removed again from localDiscoveryStore.
         *
         * If awaitGlobalRegistration is false, the call to the globalDiscovery will just be triggered, but it is not
         * being waited for results or timeout. Also, in case it does not succeed, the entry remains in
         * localDiscoveryStore.
         */
        if (discoveryEntry.getQos().getScope().equals(ProviderScope.GLOBAL)) {
            Add1Deferred deferredForRegisterGlobal;
            if (awaitGlobalRegistration == true) {
                deferredForRegisterGlobal = deferred;
            } else {
                // use an independent DeferredVoid not used for waiting
                deferredForRegisterGlobal = new Add1Deferred();
                deferred.resolve();
            }
            registerGlobal(discoveryEntry, gbids, deferredForRegisterGlobal, awaitGlobalRegistration);
        } else {
            deferred.resolve();
        }
        return new Promise<>(deferred);
    }

    @Override
    public Promise<AddToAllDeferred> addToAll(DiscoveryEntry discoveryEntry, Boolean awaitGlobalRegistration) {
        Promise<Add1Deferred> addPromise = add(discoveryEntry, awaitGlobalRegistration, knownGbids);
        AddToAllDeferred addToAllDeferred = new AddToAllDeferred();
        addPromise.then(new PromiseListener() {
            @Override
            public void onRejection(JoynrException error) {
                if (error instanceof ApplicationException) {
                    addToAllDeferred.reject(((ApplicationException) error).getError());
                } else if (error instanceof ProviderRuntimeException) {
                    addToAllDeferred.reject((ProviderRuntimeException) error);
                } else {
                    addToAllDeferred.reject(new ProviderRuntimeException("Unknown error registering provider "
                            + discoveryEntry.getParticipantId() + " in all known backends: " + error));
                }
            }

            @Override
            public void onFulfillment(Object... values) {
                addToAllDeferred.resolve();
            }
        });
        return new Promise<>(addToAllDeferred);
    }

    private void registerGlobal(final DiscoveryEntry discoveryEntry,
                                final String[] gbids,
                                final Add1Deferred deferred,
                                final boolean awaitGlobalRegistration) {
        synchronized (globalAddressLock) {
            try {
                globalAddress = globalAddressProvider.get();
            } catch (Exception e) {
                logger.debug("Error getting global address", e);
                globalAddress = null;
            }

            if (globalAddress == null) {
                queuedDiscoveryEntries.add(new QueuedDiscoveryEntry(discoveryEntry,
                                                                    gbids,
                                                                    deferred,
                                                                    awaitGlobalRegistration));
                globalAddressProvider.registerGlobalAddressesReadyListener(this);
                return;
            }
        }

        final GlobalDiscoveryEntry globalDiscoveryEntry = CapabilityUtils.discoveryEntry2GlobalDiscoveryEntry(discoveryEntry,
                                                                                                              globalAddress);
        if (globalDiscoveryEntry != null) {
            boolean doRetry = !awaitGlobalRegistration;
            long expiryDateMs = doRetry ? Long.MAX_VALUE : System.currentTimeMillis() + defaultTtlAddAndRemove;

            CallbackWithModeledError<Void, DiscoveryError> callback = new CallbackWithModeledError<Void, DiscoveryError>() {

                @Override
                public void onSuccess(Void nothing) {
                    logger.info("Global provider registration succeeded: participantId {}, domain {}, interface {}, {}",
                                globalDiscoveryEntry.getParticipantId(),
                                globalDiscoveryEntry.getDomain(),
                                globalDiscoveryEntry.getInterfaceName(),
                                globalDiscoveryEntry.getProviderVersion());
                    gcdTaskSequencer.taskFinished();
                    deferred.resolve();
                }

                @Override
                public void onFailure(JoynrRuntimeException exception) {
                    if (doRetry && exception instanceof JoynrTimeoutException) {
                        logger.warn("Global provider registration failed due to timeout, retrying: participantId {}, domain {}, interface {}, {}",
                                    globalDiscoveryEntry.getParticipantId(),
                                    globalDiscoveryEntry.getDomain(),
                                    globalDiscoveryEntry.getInterfaceName(),
                                    globalDiscoveryEntry.getProviderVersion(),
                                    exception);
                        gcdTaskSequencer.retryTask();
                    } else {
                        logger.error("Global provider registration failed: participantId {}, domain {}, interface {}, {}",
                                     globalDiscoveryEntry.getParticipantId(),
                                     globalDiscoveryEntry.getDomain(),
                                     globalDiscoveryEntry.getInterfaceName(),
                                     globalDiscoveryEntry.getProviderVersion(),
                                     exception);
                        if (awaitGlobalRegistration == true) {
                            globalProviderParticipantIdToGbidListMap.remove(globalDiscoveryEntry.getParticipantId());
                            localDiscoveryEntryStore.remove(globalDiscoveryEntry.getParticipantId());
                        }
                        gcdTaskSequencer.taskFinished();
                        deferred.reject(new ProviderRuntimeException(exception.toString()));
                    }
                }

                @Override
                public void onFailure(DiscoveryError errorEnum) {
                    logger.error("Global provider registration failed: participantId {}, domain {}, interface {}, {}",
                                 globalDiscoveryEntry.getParticipantId(),
                                 globalDiscoveryEntry.getDomain(),
                                 globalDiscoveryEntry.getInterfaceName(),
                                 globalDiscoveryEntry.getProviderVersion());
                    if (awaitGlobalRegistration == true) {
                        globalProviderParticipantIdToGbidListMap.remove(globalDiscoveryEntry.getParticipantId());
                        localDiscoveryEntryStore.remove(globalDiscoveryEntry.getParticipantId());
                    }
                    gcdTaskSequencer.taskFinished();
                    deferred.reject(errorEnum);
                }
            };
            GcdTask addTask = GcdTask.createAddTask(callback, globalDiscoveryEntry, expiryDateMs, gbids, doRetry);
            logger.debug("Global provider registration scheduled: participantId {}, domain {}, interface {}, {}, awaitGlobalRegistration {}",
                         globalDiscoveryEntry.getParticipantId(),
                         globalDiscoveryEntry.getDomain(),
                         globalDiscoveryEntry.getInterfaceName(),
                         globalDiscoveryEntry.getProviderVersion(),
                         awaitGlobalRegistration);
            gcdTaskSequencer.addTask(addTask);
        }
    }

    @Override
    public io.joynr.provider.Promise<io.joynr.provider.DeferredVoid> remove(String participantId) {
        DeferredVoid deferred = new DeferredVoid();
        Optional<DiscoveryEntry> optionalDiscoveryEntry = localDiscoveryEntryStore.lookup(participantId,
                                                                                          Long.MAX_VALUE);
        if (optionalDiscoveryEntry.isPresent()) {
            removeInternal(optionalDiscoveryEntry.get().getParticipantId(),
                           optionalDiscoveryEntry.get().getQos().getScope());
        } else {
            removeInternal(participantId, ProviderScope.GLOBAL);
        }
        deferred.resolve();
        return new Promise<>(deferred);
    }

    private void removeInternal(final String participantId, ProviderScope providerScope) {
        if (providerScope == ProviderScope.LOCAL) {
            localDiscoveryEntryStore.remove(participantId);
            logger.info("Removed locally registered participantId {}", participantId);
        } else {
            CallbackWithModeledError<Void, DiscoveryError> callback = new CallbackWithModeledError<Void, DiscoveryError>() {

                @Override
                public void onSuccess(Void result) {
                    synchronized (globalDiscoveryEntryCache) {
                        globalProviderParticipantIdToGbidListMap.remove(participantId);
                        localDiscoveryEntryStore.remove(participantId);
                    }
                    logger.info("Removed globally registered participantId {}", participantId);
                    gcdTaskSequencer.taskFinished();
                }

                @Override
                public void onFailure(JoynrRuntimeException error) {
                    //check for instance of JoynrTimeoutException for retrying
                    if (error instanceof JoynrTimeoutException) {
                        logger.warn("Failed to remove participantId {} due to timeout, retrying", participantId, error);
                        gcdTaskSequencer.retryTask();
                    } else {
                        logger.warn("Failed to remove participantId {}: {}", participantId, error);
                        gcdTaskSequencer.taskFinished();
                    }
                }

                @Override
                public void onFailure(DiscoveryError errorEnum) {
                    switch (errorEnum) {
                    case NO_ENTRY_FOR_PARTICIPANT:
                    case NO_ENTRY_FOR_SELECTED_BACKENDS:
                        // already removed globally
                        logger.warn("Error removing participantId {} globally: {}. Removing local entry.",
                                    participantId,
                                    errorEnum);
                        synchronized (globalDiscoveryEntryCache) {
                            globalProviderParticipantIdToGbidListMap.remove(participantId);
                            localDiscoveryEntryStore.remove(participantId);
                        }
                        break;
                    case INVALID_GBID:
                    case UNKNOWN_GBID:
                    case INTERNAL_ERROR:
                    default:
                        // do nothing
                        logger.warn("Failed to remove participantId {}: {}", participantId, errorEnum);
                    }
                    gcdTaskSequencer.taskFinished();
                }
            };
            GcdTask removeTask = GcdTask.createRemoveTask(callback, participantId);
            logger.debug("Global remove scheduled, participantId {}", participantId);
            gcdTaskSequencer.addTask(removeTask);
        }
    }

    @Override
    public Promise<Lookup1Deferred> lookup(String[] domains, String interfaceName, DiscoveryQos discoveryQos) {
        Promise<Lookup2Deferred> lookupPromise = lookup(domains, interfaceName, discoveryQos, new String[]{});
        Lookup1Deferred lookup1Deferred = new Lookup1Deferred();
        lookupPromise.then(new PromiseListener() {
            @Override
            public void onRejection(JoynrException exception) {
                if (exception instanceof ApplicationException) {
                    DiscoveryError error = ((ApplicationException) exception).getError();
                    lookup1Deferred.reject(new ProviderRuntimeException("Error discovering provider for domain "
                            + Arrays.toString(domains) + " and interface " + interfaceName + " in all backends: "
                            + error));
                } else if (exception instanceof ProviderRuntimeException) {
                    lookup1Deferred.reject((ProviderRuntimeException) exception);
                } else {
                    lookup1Deferred.reject(new ProviderRuntimeException("Unknown error discovering provider for domain "
                            + Arrays.toString(domains) + " and interface " + interfaceName + " in all backends: "
                            + exception));
                }
            }

            @Override
            public void onFulfillment(Object... values) {
                lookup1Deferred.resolve((DiscoveryEntryWithMetaInfo[]) values[0]);
            }
        });
        return new Promise<>(lookup1Deferred);
    }

    @Override
    public Promise<Lookup2Deferred> lookup(String[] domains,
                                           String interfaceName,
                                           DiscoveryQos discoveryQos,
                                           String[] gbids) {
        final Lookup2Deferred deferred = new Lookup2Deferred();

        DiscoveryError validationResult = validateGbids(gbids);
        if (validationResult != null) {
            deferred.reject(validationResult);
            return new Promise<>(deferred);
        }
        if (gbids.length == 0) {
            // lookup provider in all known backends
            gbids = knownGbids;
        }

        CapabilitiesCallback callback = new CapabilitiesCallback() {
            @Override
            public void processCapabilitiesReceived(Optional<Collection<DiscoveryEntryWithMetaInfo>> capabilities) {
                if (!capabilities.isPresent()) {
                    deferred.reject(new ProviderRuntimeException("Received capablities collection was null"));
                } else {
                    deferred.resolve(capabilities.get()
                                                 .toArray(new DiscoveryEntryWithMetaInfo[capabilities.get().size()]));
                }
            }

            @Override
            public void onError(Throwable e) {
                deferred.reject(new ProviderRuntimeException(e.toString()));
            }

            @Override
            public void onError(DiscoveryError error) {
                deferred.reject(error);
            }
        };
        lookup(domains, interfaceName, discoveryQos, gbids, callback);

        return new Promise<>(deferred);
    }

    private boolean isEntryForGbids(GlobalDiscoveryEntry entry, Set<String> gbidSet) {
        // globally looked up provider
        Address entryAddress;
        try {
            entryAddress = CapabilityUtils.getAddressFromGlobalDiscoveryEntry(entry);
        } catch (Exception e) {
            logger.error("Error reading address from GlobalDiscoveryEntry: {}", entry);
            return false;
        }
        if (entryAddress instanceof MqttAddress) {
            if (!gbidSet.contains(((MqttAddress) entryAddress).getBrokerUri())) {
                // globally looked up provider in wrong backend
                return false;
            }
        }
        // return true for all other address types
        return true;
    }

    private Set<GlobalDiscoveryEntry> filterGloballyCachedEntriesByGbids(Collection<GlobalDiscoveryEntry> globalEntries,
                                                                         String[] gbids) {
        Set<GlobalDiscoveryEntry> result = new HashSet<>();
        Set<String> gbidSet = new HashSet<>(Arrays.asList(gbids));
        for (GlobalDiscoveryEntry entry : globalEntries) {
            if (!isEntryForGbids(entry, gbidSet)) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    /**
     * Searches for capabilities by domain and interface name and gbids.
     *
     * @param domains The Domains for which the search is to be done.
     * @param interfaceName The interface for which the search is to be done.
     * @param discoveryQos The discovery quality of service for the search.
     * @param gbids Global Backend IDs for which (global) providers should be returned.
     * @param capabilitiesCallback Callback to deliver the results asynchronously.
     */
    public void lookup(final String[] domains,
                       final String interfaceName,
                       final DiscoveryQos discoveryQos,
                       String[] gbids,
                       final CapabilitiesCallback capabilitiesCallback) {
        DiscoveryScope discoveryScope = discoveryQos.getDiscoveryScope();
        Set<DiscoveryEntryWithMetaInfo> localEntries = getLocalEntries(discoveryScope,
                                                                       gbids,
                                                                       domains,
                                                                       interfaceName,
                                                                       discoveryQos.getCacheMaxAge());
        Set<GlobalDiscoveryEntry> cachedEntries = getCachedEntriesIfRequired(discoveryScope,
                                                                             gbids,
                                                                             domains,
                                                                             interfaceName,
                                                                             discoveryQos.getCacheMaxAge());
        switch (discoveryScope) {
        case LOCAL_ONLY:
            handleLocalOnly(capabilitiesCallback, localEntries);
            break;
        case LOCAL_THEN_GLOBAL:
            handleLocalThenGlobal(domains,
                                  interfaceName,
                                  discoveryQos,
                                  gbids,
                                  capabilitiesCallback,
                                  localEntries,
                                  cachedEntries);
            break;
        case LOCAL_AND_GLOBAL:
            handleLocalAndGlobal(domains,
                                 interfaceName,
                                 discoveryQos,
                                 gbids,
                                 capabilitiesCallback,
                                 localEntries,
                                 cachedEntries);
            break;
        case GLOBAL_ONLY:
            // localEntries only contains globally registered providers, already filtered by gbids, in case of DiscoveryScope.GLOBAL_ONLY
            handleGlobalOnly(domains,
                             interfaceName,
                             discoveryQos,
                             gbids,
                             capabilitiesCallback,
                             localEntries,
                             cachedEntries);
            break;
        default:
            throw new IllegalStateException("Unknown or illegal DiscoveryScope value: " + discoveryScope);
        }
    }

    private void handleLocalOnly(final CapabilitiesCallback capabilitiesCallback,
                                 Set<DiscoveryEntryWithMetaInfo> localDiscoveryEntries) {
        localDiscoveryEntries.forEach((entry) -> routingTable.incrementReferenceCount(entry.getParticipantId()));
        capabilitiesCallback.processCapabilitiesReceived(Optional.of(localDiscoveryEntries));
    }

    private void handleLocalThenGlobal(String[] domains,
                                       String interfaceName,
                                       DiscoveryQos discoveryQos,
                                       String[] gbids,
                                       CapabilitiesCallback capabilitiesCallback,
                                       Set<DiscoveryEntryWithMetaInfo> localDiscoveryEntries,
                                       Set<GlobalDiscoveryEntry> globalDiscoveryEntries) {
        Set<String> missingDomains = new HashSet<String>(Arrays.asList(domains));

        Set<DiscoveryEntryWithMetaInfo> result = new HashSet<DiscoveryEntryWithMetaInfo>();
        boolean globalEntriesAdded = false;

        // look for the domains in the local entries first
        for (DiscoveryEntryWithMetaInfo entry : localDiscoveryEntries) {
            result.add(entry);
            missingDomains.remove(entry.getDomain());
        }

        // If there still some missing domains, search in the globalCache
        if (!missingDomains.isEmpty()) {
            globalEntriesAdded = true;
            globalDiscoveryEntries.forEach((entry) -> {
                result.add(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(false, entry));
                missingDomains.remove(entry.getDomain());
            });
        }

        if (missingDomains.isEmpty()) {
            localDiscoveryEntries.forEach((entry) -> routingTable.incrementReferenceCount(entry.getParticipantId()));
            if (globalEntriesAdded) {
                globalDiscoveryEntries.forEach((entry) -> addToRoutingTable(entry));
            }
            capabilitiesCallback.processCapabilitiesReceived(Optional.of(result));
        } else {
            asyncGetGlobalCapabilities(domains, interfaceName, discoveryQos, gbids, capabilitiesCallback, result);
        }
    }

    private void handleLocalAndGlobal(String[] domains,
                                      String interfaceName,
                                      DiscoveryQos discoveryQos,
                                      String[] gbids,
                                      CapabilitiesCallback capabilitiesCallback,
                                      Set<DiscoveryEntryWithMetaInfo> localDiscoveryEntries,
                                      Set<GlobalDiscoveryEntry> globalDiscoveryEntries) {
        Set<String> missingDomains = new HashSet<String>(Arrays.asList(domains));
        Set<DiscoveryEntryWithMetaInfo> result = new HashSet<DiscoveryEntryWithMetaInfo>(localDiscoveryEntries);

        // ParticipantIds are unique across the local store and the global cache
        // see add method and asyncGetGlobalCapabilities/asyncGetGlobalCapability
        globalDiscoveryEntries.forEach((entry) -> result.add(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(false,
                                                                                                                 entry)));

        for (GlobalDiscoveryEntry globalEntry : globalDiscoveryEntries) {
            missingDomains.remove(globalEntry.getDomain());
        }

        if (missingDomains.isEmpty()) {
            localDiscoveryEntries.forEach((entry) -> routingTable.incrementReferenceCount(entry.getParticipantId()));
            globalDiscoveryEntries.forEach((entry) -> addToRoutingTable(entry));
            capabilitiesCallback.processCapabilitiesReceived(Optional.of(result));
        } else {
            asyncGetGlobalCapabilities(domains, interfaceName, discoveryQos, gbids, capabilitiesCallback, result);
        }
    }

    private void handleGlobalOnly(String[] domains,
                                  String interfaceName,
                                  DiscoveryQos discoveryQos,
                                  String[] gbids,
                                  CapabilitiesCallback capabilitiesCallback,
                                  Set<DiscoveryEntryWithMetaInfo> localEntries,
                                  Set<GlobalDiscoveryEntry> globalDiscoveryEntries) {
        Set<String> missingDomains = new HashSet<>(Arrays.asList(domains));
        Set<DiscoveryEntryWithMetaInfo> result = new HashSet<DiscoveryEntryWithMetaInfo>(localEntries);
        // ParticipantIds are unique across the local store and the global cache
        // see add method and asyncGetGlobalCapabilities/asyncGetGlobalCapability
        globalDiscoveryEntries.forEach((entry) -> result.add(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(false,
                                                                                                                 entry)));
        for (DiscoveryEntryWithMetaInfo entry : result) {
            missingDomains.remove(entry.getDomain());
        }

        if (missingDomains.isEmpty()) {
            localEntries.forEach((entry) -> routingTable.incrementReferenceCount(entry.getParticipantId()));
            globalDiscoveryEntries.forEach((entry) -> addToRoutingTable(entry));
            capabilitiesCallback.processCapabilitiesReceived(Optional.of(result));
        } else {
            asyncGetGlobalCapabilities(domains, interfaceName, discoveryQos, gbids, capabilitiesCallback, result);
        }
    }

    private Set<GlobalDiscoveryEntry> getCachedEntriesIfRequired(DiscoveryScope discoveryScope,
                                                                 String[] gbids,
                                                                 String[] domains,
                                                                 String interfaceName,
                                                                 long cacheMaxAge) {
        if (INCLUDE_GLOBAL_SCOPES.contains(discoveryScope)) {
            Collection<GlobalDiscoveryEntry> globallyCachedEntries = globalDiscoveryEntryCache.lookup(domains,
                                                                                                      interfaceName,
                                                                                                      cacheMaxAge);
            return filterGloballyCachedEntriesByGbids(globallyCachedEntries, gbids);
        }
        return null;
    }

    private boolean isRegisteredForGbids(DiscoveryEntry entry, Collection<String> gbidSet) {
        List<String> entryBackends;
        synchronized (globalDiscoveryEntryCache) {
            entryBackends = globalProviderParticipantIdToGbidListMap.get(entry.getParticipantId());
        }
        if (entryBackends == null) {
            return false;
        }
        // local provider which is globally registered
        if (gbidSet.stream().anyMatch(gbid -> entryBackends.contains(gbid))) {
            return true;
        }
        return false;
    }

    private Collection<DiscoveryEntry> filterLocalEntriesByGbids(Collection<DiscoveryEntry> localEntries,
                                                                 String[] gbids) {
        Set<DiscoveryEntry> result = new HashSet<>();
        Set<String> gbidSet = new HashSet<>(Arrays.asList(gbids));
        for (DiscoveryEntry entry : localEntries) {
            if (!isRegisteredForGbids(entry, gbidSet)) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    private Set<DiscoveryEntryWithMetaInfo> getLocalEntries(DiscoveryScope discoveryScope,
                                                            String[] gbids,
                                                            String[] domains,
                                                            String interfaceName,
                                                            long cacheMaxAge) {
        if (INCLUDE_LOCAL_SCOPES.contains(discoveryScope)) {
            // return all local entries (locally and globally registered) if DiscoveryScope includes local entries
            return CapabilityUtils.convertToDiscoveryEntryWithMetaInfoSet(true,
                                                                          localDiscoveryEntryStore.lookup(domains,
                                                                                                          interfaceName));
        } else if (cacheMaxAge > 0) {
            Collection<DiscoveryEntry> localEntries = localDiscoveryEntryStore.lookupGlobalEntries(domains,
                                                                                                   interfaceName);
            // return only globally registered local providers for selected GBIDs if DiscoveryScope does not include local entries
            Collection<DiscoveryEntry> filteredLocalEntries = filterLocalEntriesByGbids(localEntries, gbids);
            return CapabilityUtils.convertToDiscoveryEntryWithMetaInfoSet(true, filteredLocalEntries);
        }
        return new HashSet<>();
    }

    @Override
    public Promise<Lookup3Deferred> lookup(String participantId) {
        DiscoveryQos discoveryQos = new DiscoveryQos(Long.MAX_VALUE,
                                                     Long.MAX_VALUE,
                                                     DiscoveryScope.LOCAL_AND_GLOBAL,
                                                     false);
        Promise<Lookup4Deferred> lookupPromise = lookup(participantId, discoveryQos, new String[]{});
        Lookup3Deferred lookup3Deferred = new Lookup3Deferred();
        lookupPromise.then(new PromiseListener() {
            @Override
            public void onRejection(JoynrException exception) {
                if (exception instanceof ApplicationException) {
                    DiscoveryError error = ((ApplicationException) exception).getError();
                    lookup3Deferred.reject(new ProviderRuntimeException("Error discovering provider " + participantId
                            + " in all backends: " + error));
                } else if (exception instanceof ProviderRuntimeException) {
                    lookup3Deferred.reject((ProviderRuntimeException) exception);
                } else {
                    lookup3Deferred.reject(new ProviderRuntimeException("Unknown error discovering provider "
                            + participantId + " in all backends: " + exception));
                }
            }

            @Override
            public void onFulfillment(Object... values) {
                lookup3Deferred.resolve((DiscoveryEntryWithMetaInfo) values[0]);
            }
        });
        return new Promise<>(lookup3Deferred);
    }

    @Override
    public Promise<Lookup4Deferred> lookup(String participantId, DiscoveryQos discoveryQos, String[] gbids) {
        Lookup4Deferred deferred = new Lookup4Deferred();
        DiscoveryError validationResult = validateGbids(gbids);
        if (validationResult != null) {
            deferred.reject(validationResult);
            return new Promise<>(deferred);
        }
        if (gbids.length == 0) {
            // lookup provider in all known backends
            gbids = knownGbids;
        }

        lookup(participantId, discoveryQos, gbids, new CapabilityCallback() {

            @Override
            public void processCapabilityReceived(Optional<DiscoveryEntryWithMetaInfo> capability) {
                deferred.resolve(capability.isPresent() ? capability.get() : null);
            }

            @Override
            public void onError(Throwable e) {
                deferred.reject(new ProviderRuntimeException(e.toString()));
            }

            @Override
            public void onError(DiscoveryError error) {
                deferred.reject(error);
            }
        });
        return new Promise<>(deferred);
    }

    /**
     * Searches for capability by participantId and gbids. This is an asynchronous method.
     *
     * @param participantId The participant id to search for.
     * @param discoveryQos The discovery quality of service for the search.
     * @param gbids Global Backend IDs for which (global) provider should be returned.
     * @param capabilityCallback callback to return the result
     */
    public void lookup(final String participantId,
                       final DiscoveryQos discoveryQos,
                       final String[] gbids,
                       final CapabilityCallback capabilityCallback) {

        final Optional<DiscoveryEntry> localEntry = localDiscoveryEntryStore.lookup(participantId, Long.MAX_VALUE);

        DiscoveryScope discoveryScope = discoveryQos.getDiscoveryScope();
        switch (discoveryScope) {
        case LOCAL_ONLY:
            if (localEntry.isPresent()) {
                routingTable.incrementReferenceCount(localEntry.get().getParticipantId());
                capabilityCallback.processCapabilityReceived(Optional.of(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(true,
                                                                                                                             localEntry.get())));
            } else {
                logger.debug("Lookup for participantId {} with DiscoveryScope.LOCAL_ONLY failed with DiscoveryError: {}",
                             participantId,
                             DiscoveryError.NO_ENTRY_FOR_PARTICIPANT);
                capabilityCallback.onError(DiscoveryError.NO_ENTRY_FOR_PARTICIPANT);
            }
            break;
        case LOCAL_THEN_GLOBAL:
        case LOCAL_AND_GLOBAL:
            if (localEntry.isPresent()) {
                routingTable.incrementReferenceCount(localEntry.get().getParticipantId());
                capabilityCallback.processCapabilityReceived(Optional.of(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(true,
                                                                                                                             localEntry.get())));
            } else {
                asyncGetGlobalCapability(gbids, participantId, discoveryQos, capabilityCallback);
            }
            break;
        case GLOBAL_ONLY:
            if (localEntry.isPresent()) {
                Set<String> gbidsSet = new HashSet<>(Arrays.asList(gbids));
                if (!ProviderScope.GLOBAL.equals(localEntry.get().getQos().getScope())) {
                    // not globally registered
                    logger.warn("Lookup for participantId {} with DiscoveryScope.GLOBAL_ONLY found matching provider with ProviderScope.LOCAL, returning DiscoveryError.NO_ENTRY_FOR_PARTICIPANT.",
                                participantId);
                    capabilityCallback.onError(DiscoveryError.NO_ENTRY_FOR_PARTICIPANT);
                } else if (isRegisteredForGbids(localEntry.get(), gbidsSet)) {
                    routingTable.incrementReferenceCount(localEntry.get().getParticipantId());
                    // filter by selected GBIDs
                    capabilityCallback.processCapabilityReceived(Optional.of(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(true,
                                                                                                                                 localEntry.get())));
                } else {
                    capabilityCallback.onError(DiscoveryError.NO_ENTRY_FOR_SELECTED_BACKENDS);
                }
            } else {
                asyncGetGlobalCapability(gbids, participantId, discoveryQos, capabilityCallback);
            }
            break;
        default:
            break;
        }
    }

    private void addToRoutingTable(GlobalDiscoveryEntry entry) {
        if (entry.getParticipantId() != null && entry.getAddress() != null) {
            Address address = CapabilityUtils.getAddressFromGlobalDiscoveryEntry(entry);
            final boolean isGloballyVisible = (entry.getQos().getScope() == ProviderScope.GLOBAL);
            final long expiryDateMs = Long.MAX_VALUE;

            routingTable.put(entry.getParticipantId(), address, isGloballyVisible, expiryDateMs);
        }
    }

    private void asyncGetGlobalCapability(final String[] gbids,
                                          final String participantId,
                                          DiscoveryQos discoveryQos,
                                          final CapabilityCallback capabilitiesCallback) {
        Optional<GlobalDiscoveryEntry> cachedGlobalCapability = globalDiscoveryEntryCache.lookup(participantId,
                                                                                                 discoveryQos.getCacheMaxAge());

        if (cachedGlobalCapability.isPresent()
                && isEntryForGbids(cachedGlobalCapability.get(), new HashSet<>(Arrays.asList(gbids)))) {
            addToRoutingTable(cachedGlobalCapability.get());
            capabilitiesCallback.processCapabilityReceived(Optional.of(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(false,
                                                                                                                           cachedGlobalCapability.get())));
        } else {
            globalCapabilitiesDirectoryClient.lookup(new CallbackWithModeledError<GlobalDiscoveryEntry, DiscoveryError>() {

                @Override
                public void onSuccess(GlobalDiscoveryEntry newGlobalDiscoveryEntry) {
                    if (newGlobalDiscoveryEntry != null) {
                        Optional<DiscoveryEntry> localStoreEntry = localDiscoveryEntryStore.lookup(participantId,
                                                                                                   Long.MAX_VALUE);
                        if (localStoreEntry.isPresent()) {
                            routingTable.incrementReferenceCount(localStoreEntry.get().getParticipantId());
                            capabilitiesCallback.processCapabilityReceived(Optional.of(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(true,
                                                                                                                                           localStoreEntry.get())));
                        } else {
                            addToRoutingTable(newGlobalDiscoveryEntry);
                            globalDiscoveryEntryCache.add(newGlobalDiscoveryEntry);
                            // No need to filter the received GDE by GBIDs: already done in GCD
                            capabilitiesCallback.processCapabilityReceived(Optional.of(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(false,
                                                                                                                                           newGlobalDiscoveryEntry)));
                        }
                    } else {
                        capabilitiesCallback.onError(new NullPointerException("Received capabilities are null"));
                    }
                }

                @Override
                public void onFailure(DiscoveryError errorEnum) {
                    logger.debug("Global lookup for participantId {} failed with DiscoveryError: {}",
                                 participantId,
                                 errorEnum);
                    capabilitiesCallback.onError(errorEnum);
                }

                @Override
                public void onFailure(JoynrRuntimeException exception) {
                    logger.debug("Global lookup for participantId {} failed with exception: {}",
                                 participantId,
                                 exception);
                    capabilitiesCallback.onError(exception);
                }
            }, participantId, discoveryQos.getDiscoveryTimeout(), gbids);
        }

    }

    /**
     * mixes in the localDiscoveryEntries to global capabilities found by participantId
     */
    private void asyncGetGlobalCapabilities(final String[] domains,
                                            final String interfaceName,
                                            DiscoveryQos discoveryQos,
                                            final String[] gbids,
                                            final CapabilitiesCallback capabilitiesCallback,
                                            Collection<DiscoveryEntryWithMetaInfo> localDiscoveryEntries2) {

        final Collection<DiscoveryEntryWithMetaInfo> localDiscoveryEntries = localDiscoveryEntries2 == null
                ? new LinkedList<DiscoveryEntryWithMetaInfo>()
                : localDiscoveryEntries2;

        globalCapabilitiesDirectoryClient.lookup(new CallbackWithModeledError<List<GlobalDiscoveryEntry>, DiscoveryError>() {

            @Override
            public void onSuccess(List<GlobalDiscoveryEntry> globalDiscoverEntries) {
                if (globalDiscoverEntries != null) {
                    Collection<DiscoveryEntryWithMetaInfo> allDiscoveryEntries = new ArrayList<DiscoveryEntryWithMetaInfo>();
                    Collection<String> addedParticipantIds = new ArrayList<String>();
                    synchronized (globalDiscoveryEntryCache) {
                        for (GlobalDiscoveryEntry entry : globalDiscoverEntries) {
                            Optional<DiscoveryEntry> localStoreEntry = localDiscoveryEntryStore.lookup(entry.getParticipantId(),
                                                                                                       Long.MAX_VALUE);
                            if (localStoreEntry.isPresent()) {
                                DiscoveryEntry localEntry = localStoreEntry.get();
                                boolean returnLocalEntry = !(discoveryQos.getDiscoveryScope()
                                                                         .equals(DiscoveryScope.GLOBAL_ONLY))
                                        || localEntry.getQos().getScope().equals(ProviderScope.GLOBAL);
                                Set<String> domainSet = new HashSet<String>(Arrays.asList(domains));
                                if (returnLocalEntry && interfaceName.equals(localEntry.getInterfaceName())
                                        && domainSet.contains(localEntry.getDomain())) {
                                    addedParticipantIds.add(entry.getParticipantId());
                                    allDiscoveryEntries.add(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(true,
                                                                                                                localStoreEntry.get()));
                                    routingTable.incrementReferenceCount(localStoreEntry.get().getParticipantId());
                                }
                                continue;
                            }
                            addToRoutingTable(entry);
                            globalDiscoveryEntryCache.add(entry);
                            addedParticipantIds.add(entry.getParticipantId());

                            // No need to filter the received GDEs by GBIDs: already done in GCD
                            allDiscoveryEntries.add(CapabilityUtils.convertToDiscoveryEntryWithMetaInfo(false, entry));
                        }
                    }
                    for (DiscoveryEntryWithMetaInfo entry : localDiscoveryEntries) {
                        if (!addedParticipantIds.contains(entry.getParticipantId())) {
                            if (entry.getIsLocal()) {
                                routingTable.incrementReferenceCount(entry.getParticipantId());
                            } else {
                                Optional<GlobalDiscoveryEntry> cachedEntry = globalDiscoveryEntryCache.lookup(entry.getParticipantId(),
                                                                                                              Long.MAX_VALUE);
                                if (cachedEntry.isPresent()) {
                                    addToRoutingTable(cachedEntry.get());
                                } else {
                                    continue;
                                }
                            }
                            allDiscoveryEntries.add(entry);
                        }
                    }
                    capabilitiesCallback.processCapabilitiesReceived(Optional.of(allDiscoveryEntries));
                } else {
                    capabilitiesCallback.onError(new NullPointerException("Received capabilities are null"));
                }
            }

            @Override
            public void onFailure(DiscoveryError errorEnum) {
                logger.debug("Global lookup for domains {} and interface {} failed with DiscoveryError: {}",
                             Arrays.toString(domains),
                             interfaceName,
                             errorEnum);
                capabilitiesCallback.onError(errorEnum);
            }

            @Override
            public void onFailure(JoynrRuntimeException exception) {
                logger.debug("Global lookup for domains {} and interface {} failed with exception: {}",
                             Arrays.toString(domains),
                             interfaceName,
                             exception);
                capabilitiesCallback.onError(exception);
            }
        }, domains, interfaceName, discoveryQos.getDiscoveryTimeout(), gbids);
    }

    @Override
    public void shutdown(boolean unregisterAllRegisteredCapabilities) {
        logger.debug("shutdown invoked");

        gcdTaskSequencer.stop();
        if (freshnessUpdateScheduledFuture != null) {
            freshnessUpdateScheduledFuture.cancel(false);
        }

        if (reAddAllGlobalEntriesScheduledFuture != null) {
            reAddAllGlobalEntriesScheduledFuture.cancel(false);
        }

        if (unregisterAllRegisteredCapabilities) {
            Set<DiscoveryEntry> allDiscoveryEntries = localDiscoveryEntryStore.getAllDiscoveryEntries();

            List<DiscoveryEntry> discoveryEntries = new ArrayList<>(allDiscoveryEntries.size());

            for (DiscoveryEntry capabilityEntry : allDiscoveryEntries) {
                if (capabilityEntry.getQos().getScope() == ProviderScope.GLOBAL) {
                    discoveryEntries.add(capabilityEntry);
                }
            }

            if (discoveryEntries.size() > 0) {
                try {
                    CallbackWithModeledError<Void, DiscoveryError> callback = new CallbackWithModeledError<Void, DiscoveryError>() {

                        @Override
                        public void onFailure(JoynrRuntimeException error) {
                        }

                        @Override
                        public void onSuccess(Void result) {
                        }

                        @Override
                        public void onFailure(DiscoveryError errorEnum) {
                        }

                    };

                    List<String> participantIds = discoveryEntries.stream()
                                                                  .filter(Objects::nonNull)
                                                                  .map(dEntry -> dEntry.getParticipantId())
                                                                  .collect(Collectors.toList());
                    for (String participantId : participantIds) {
                        synchronized (globalDiscoveryEntryCache) {
                            List<String> gbidList = globalProviderParticipantIdToGbidListMap.get(participantId);
                            if (gbidList != null) {
                                globalCapabilitiesDirectoryClient.remove(callback,
                                                                         participantId,
                                                                         gbidList.toArray(new String[0]));
                            }
                        }
                    }
                } catch (DiscoveryException e) {
                    logger.debug("Error removing discovery entries", e);
                }
            }
        }
        logger.debug("shutdown finished");
    }

    @Override
    public Set<DiscoveryEntry> listLocalCapabilities() {
        return localDiscoveryEntryStore.getAllDiscoveryEntries();
    }

    @Override
    public void transportReady(Optional<Address> address) {
        synchronized (globalAddressLock) {
            globalAddress = address.isPresent() ? address.get() : null;
        }
        for (QueuedDiscoveryEntry queuedDiscoveryEntry : queuedDiscoveryEntries) {
            registerGlobal(queuedDiscoveryEntry.getDiscoveryEntry(),
                           queuedDiscoveryEntry.getGbids(),
                           queuedDiscoveryEntry.getDeferred(),
                           queuedDiscoveryEntry.getAwaitGlobalRegistration());
        }
    }

    @Override
    public void removeStaleProvidersOfClusterController() {
        for (String gbid : knownGbids) {
            removeStaleProvidersOfClusterController(gbid);
        }
    }

    private void removeStaleProvidersOfClusterController(String gbid) {
        Callback<Void> callback = new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                logger.info("RemoveStale in gbid={} (maxLastSeenDateMs={}) succeeded.", gbid, ccStartUpDateInMs);
            }

            @Override
            public void onFailure(JoynrRuntimeException error) {
                logger.error("RemoveStale in gbid={} (maxLastSeenDateMs={}) failed.", gbid, ccStartUpDateInMs, error);
                if (!(error instanceof JoynrMessageNotSentException
                        && error.getMessage().contains("Address type not supported"))) {
                    removeStaleProvidersOfClusterController(gbid);
                }
            }
        };
        globalCapabilitiesDirectoryClient.removeStale(callback, ccStartUpDateInMs, gbid);
    }

    private void reAddAllGlobalDiscoveryEntriesPeriodically() {
        GcdTask reAddTask = GcdTask.createReaddTask();
        Runnable command = new Runnable() {
            @Override
            public void run() {
                logger.debug("Re-Add scheduled.");
                gcdTaskSequencer.addTask(reAddTask);
            }
        };
        reAddAllGlobalEntriesScheduledFuture = scheduler.scheduleAtFixedRate(command,
                                                                             READD_INTERVAL_DAYS,
                                                                             READD_INTERVAL_DAYS,
                                                                             TimeUnit.DAYS);
    }

    public class GcdTaskSequencer implements Runnable {

        private Logger logger = LoggerFactory.getLogger(GcdTaskSequencer.class);
        private volatile boolean isStopped = false;
        private volatile GcdTask task;
        private final ConcurrentLinkedQueue<GcdTask> taskQueue;
        private Semaphore queueSemaphore;
        private Semaphore workerSemaphore;

        public GcdTaskSequencer() {
            workerSemaphore = new Semaphore(1);
            queueSemaphore = new Semaphore(0);
            taskQueue = new ConcurrentLinkedQueue<>();
        }

        public void stop() {
            isStopped = true;
            taskQueue.clear();
            queueSemaphore.release();
            workerSemaphore.release();
        }

        public void addTask(GcdTask task) {
            taskQueue.add(task);
            queueSemaphore.release();
        }

        public void retryTask() {
            workerSemaphore.release();
        }

        public void taskFinished() {
            task = null;
            workerSemaphore.release();
        }

        private long removeExpiredAndGetNextWaitTime() {
            long timeTillNextExpiration = defaultTtlAddAndRemove;
            while (true) {
                GcdTask expiredTask = null;
                boolean foundExpiredEntry = false;
                for (GcdTask task : taskQueue) {
                    if (task.mode == GcdTask.MODE.ADD) {
                        timeTillNextExpiration = task.expiryDateMs - System.currentTimeMillis();
                        if ((task.expiryDateMs) <= System.currentTimeMillis()) {
                            expiredTask = task;
                            foundExpiredEntry = true;
                            break;
                        }
                        if (timeTillNextExpiration > defaultExpiryTimeMs) {
                            timeTillNextExpiration = defaultExpiryTimeMs;
                        }
                        break;
                    }
                }
                if (foundExpiredEntry) {
                    queueSemaphore.acquireUninterruptibly();
                    expiredTask.callback.onFailure(new JoynrRuntimeException("Failed to process global registration in time, please try again"));
                    workerSemaphore.acquireUninterruptibly();
                    taskQueue.remove(expiredTask);
                    continue;
                }
                break;
            }
            return timeTillNextExpiration;
        }

        @Override
        public void run() {
            while (!isStopped) {
                long timeTillNextExpiration = removeExpiredAndGetNextWaitTime();

                try {
                    if (!workerSemaphore.tryAcquire(timeTillNextExpiration, TimeUnit.MILLISECONDS)) {
                        continue;
                    }
                } catch (InterruptedException e) {
                    logger.error("workerSemaphore.acquire() interrupted", e);
                    continue;
                }
                if (isStopped) {
                    break;
                }

                if (task == null) {
                    // get new task, else: retry previous task
                    try {
                        queueSemaphore.acquire();
                    } catch (InterruptedException e) {
                        logger.error("queueSemaphore.acquire() interrupted", e);
                        workerSemaphore.release();
                        continue;
                    }
                    if (isStopped) {
                        break;
                    }
                    task = taskQueue.poll();
                    if (task == null) {
                        logger.debug("Retrieved addRemoveQueueEntry is null. Skipping and continuing.");
                        workerSemaphore.release();
                        continue;
                    }
                }

                switch (task.mode) {
                case ADD:
                    if (task.doRetry) {
                        performAdd(defaultTtlAddAndRemove);
                        break;
                    }
                    long remainingTtl = task.expiryDateMs - System.currentTimeMillis();
                    if (task.expiryDateMs < System.currentTimeMillis()) {
                        task.callback.onFailure(new JoynrRuntimeException("Failed to process global registration in time, please try again"));
                        continue;
                    }
                    performAdd(remainingTtl);
                    break;
                case READD:
                    performReAdd();
                    break;
                case REMOVE:
                    performRemove();
                    break;
                default:
                    logger.error("Unknown operation in GlobalAddRemoveQueue.");
                    taskFinished();
                }
            }
        }

        private void performAdd(long ttlMs) {
            logger.debug("Global provider registration started: participantId {}, domain {}, interface {}, {}",
                         task.globalDiscoveryEntry.getParticipantId(),
                         task.globalDiscoveryEntry.getDomain(),
                         task.globalDiscoveryEntry.getInterfaceName(),
                         task.globalDiscoveryEntry.getProviderVersion());
            try {
                globalCapabilitiesDirectoryClient.add(task.callback, task.globalDiscoveryEntry, ttlMs, task.gbids);
            } catch (Exception exception) {
                if (exception instanceof JoynrRuntimeException) {
                    task.callback.onFailure((JoynrRuntimeException) exception);
                } else {
                    task.callback.onFailure(new JoynrRuntimeException("Global registration failed: "
                            + exception.toString()));
                }
            }
        }

        private void performReAdd() {
            logger.info("Re-Add started.");
            Set<DiscoveryEntry> discoveryEntries;
            synchronized (globalDiscoveryEntryCache) {
                discoveryEntries = localDiscoveryEntryStore.getAllGlobalEntries();
            }

            if (discoveryEntries == null || discoveryEntries.isEmpty()) {
                logger.debug("Re-Add: no globally registered providers found.");
                taskFinished();
                return;
            }

            CountDownLatch cdlReAdd = new CountDownLatch(discoveryEntries.size());
            for (DiscoveryEntry discoveryEntry : discoveryEntries) {
                final GlobalDiscoveryEntry globalDiscoveryEntry = CapabilityUtils.discoveryEntry2GlobalDiscoveryEntry(discoveryEntry,
                                                                                                                      globalAddress);

                String[] gbids;
                synchronized (globalDiscoveryEntryCache) {
                    if (globalProviderParticipantIdToGbidListMap.containsKey(discoveryEntry.getParticipantId())) {
                        List<String> gbidsList = globalProviderParticipantIdToGbidListMap.get(discoveryEntry.getParticipantId());
                        gbids = gbidsList.toArray(new String[gbidsList.size()]);
                    } else {
                        logger.warn("Re-Add: no GBIDs found for {}", globalDiscoveryEntry.getParticipantId());
                        continue;
                    }
                }

                AtomicBoolean callbackCalled = new AtomicBoolean(false);
                CallbackWithModeledError<Void, DiscoveryError> callback = new CallbackWithModeledError<Void, DiscoveryError>() {

                    @Override
                    public void onSuccess(Void nothing) {
                        logger.info("Re-Add succeeded for {}.", globalDiscoveryEntry.getParticipantId());
                        if (callbackCalled.compareAndSet(false, true)) {
                            cdlReAdd.countDown();
                        }
                    }

                    @Override
                    public void onFailure(JoynrRuntimeException exception) {
                        logger.error("Re-Add failed for {} with exception.",
                                     globalDiscoveryEntry.getParticipantId(),
                                     exception);
                        if (callbackCalled.compareAndSet(false, true)) {
                            cdlReAdd.countDown();
                        }
                    }

                    @Override
                    public void onFailure(DiscoveryError errorEnum) {
                        logger.error("Re-Add failed for {} with error {}.",
                                     globalDiscoveryEntry.getParticipantId(),
                                     errorEnum);
                        if (callbackCalled.compareAndSet(false, true)) {
                            cdlReAdd.countDown();
                        }
                    }
                };
                try {
                    globalCapabilitiesDirectoryClient.add(callback,
                                                          globalDiscoveryEntry,
                                                          defaultTtlAddAndRemove,
                                                          gbids);
                } catch (Exception exception) {
                    callback.onFailure(new JoynrRuntimeException("Re-Add failed for "
                            + globalDiscoveryEntry.getParticipantId() + ": " + exception.toString()));
                }
            }

            try {
                logger.trace("Re-Add: waiting for completion.");
                if (cdlReAdd.await(defaultTtlAddAndRemove, TimeUnit.MILLISECONDS)) {
                    logger.info("Re-Add completed.");
                } else {
                    logger.error("Re-Add: timed out waiting for completion.");
                }
            } catch (InterruptedException e) {
                logger.error("Re-Add: interrupted while waiting for completion.", e);
            }
            taskFinished();
        }

        private void performRemove() {
            synchronized (globalDiscoveryEntryCache) {
                if (globalProviderParticipantIdToGbidListMap.containsKey(task.participantId)) {
                    List<String> gbidsToRemove = globalProviderParticipantIdToGbidListMap.get(task.participantId);
                    logger.info("Removing globally registered participantId {} for GBIDs {}",
                                task.participantId,
                                gbidsToRemove);
                    try {
                        globalCapabilitiesDirectoryClient.remove(task.callback,
                                                                 task.participantId,
                                                                 gbidsToRemove.toArray(new String[gbidsToRemove.size()]));
                    } catch (Exception exception) {
                        if (exception instanceof JoynrRuntimeException) {
                            task.callback.onFailure((JoynrRuntimeException) exception);
                        } else {
                            task.callback.onFailure(new JoynrRuntimeException("Global remove failed: "
                                    + exception.toString()));
                        }
                    }
                } else {
                    logger.warn("Participant {} is not registered globally and cannot be removed!", task.participantId);
                    taskFinished();
                }
            }
        }
    }
}
