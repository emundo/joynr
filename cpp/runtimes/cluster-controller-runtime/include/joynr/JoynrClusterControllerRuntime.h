/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2020 BMW Car IT GmbH
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

#ifndef JOYNRCLUSTERCONTROLLERRUNTIME_H
#define JOYNRCLUSTERCONTROLLERRUNTIME_H

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include <map>

#include "joynr/ClusterControllerSettings.h"
#include "joynr/IClusterControllerSignalHandler.h"
#include "joynr/JoynrClusterControllerRuntimeExport.h"
#include "joynr/JoynrRuntime.h"
#include "joynr/LibjoynrSettings.h"
#include "joynr/Logger.h"
#include "joynr/ParticipantIdStorage.h"
#include "joynr/PrivateCopyAssign.h"
#include "joynr/Semaphore.h"
#include "joynr/Settings.h"
#include "joynr/SystemServicesSettings.h"
#include "joynr/UdsServer.h"
#include "joynr/UdsSettings.h"
#include "joynr/WebSocketSettings.h"
#include "joynr/system/RoutingTypes/Address.h"
#include "joynr/types/CustomParameter.h"
#include "joynr/types/ProviderQos.h"
#include "joynr/types/ProviderScope.h"

class JoynrClusterControllerRuntimeTest;

namespace joynr
{

class AbstractGlobalMessagingSkeleton;
class AccessControlListEditor;
class AccessController;
class CcMessageRouter;
class IDispatcher;
class IKeychain;
class IMessageRouter;
class IMessageSender;
class ITransportMessageReceiver;
class ITransportMessageSender;
class IWebsocketCcMessagingSkeleton;
class InProcessMessagingSkeleton;
class JoynrClusterControllerMqttConnectionData;
class GlobalCapabilitiesDirectoryClient;
class LocalCapabilitiesDirectory;
class LocalCapabilitiesDirectoryStore;
class LocalDomainAccessController;
class MqttReceiver;
class MulticastMessagingSkeletonDirectory;
class Settings;
class SubscriptionManager;
class UdsCcMessagingSkeleton;
class UdsMessagingStubFactory;
class WebSocketMessagingStubFactory;

namespace capabilities
{
class CachingStorage;
class Storage;
}

namespace types
{
class DiscoveryEntryWithMetaInfo;
}

class JOYNRCLUSTERCONTROLLERRUNTIME_EXPORT JoynrClusterControllerRuntime
        : public JoynrRuntimeImpl,
          public IClusterControllerSignalHandler
{
public:
    using MqttMessagingSkeletonFactory =
            std::function<std::shared_ptr<AbstractGlobalMessagingSkeleton>(
                    std::weak_ptr<IMessageRouter> messageRouter,
                    std::shared_ptr<MqttReceiver> mqttReceiver,
                    const std::string& gbid,
                    const std::string& multicastTopicPrefix,
                    std::uint64_t ttlUplift)>;
    JoynrClusterControllerRuntime(
            std::unique_ptr<Settings> settings,
            std::function<void(const exceptions::JoynrRuntimeException&)>&& onFatalRuntimeError,
            std::shared_ptr<IKeychain> _keyChain = nullptr,
            MqttMessagingSkeletonFactory mqttMessagingSkeletonFactory = nullptr,
            std::int64_t removeStaleDelayMs = _defaultRemoveStaleDelayMs);

    DISALLOW_COPY_AND_ASSIGN(JoynrClusterControllerRuntime);

    static std::shared_ptr<JoynrClusterControllerRuntime> create(
            std::size_t argc,
            char* argv[],
            std::shared_ptr<IKeychain> _keyChain = nullptr,
            MqttMessagingSkeletonFactory mqttMessagingSkeletonFactory = nullptr);

    static std::shared_ptr<JoynrClusterControllerRuntime> create(
            std::unique_ptr<Settings> settings,
            std::function<void(const exceptions::JoynrRuntimeException&)> onFatalRuntimeError,
            const std::string& discoveryEntriesFile = "",
            std::shared_ptr<IKeychain> _keyChain = nullptr,
            MqttMessagingSkeletonFactory mqttMessagingSkeletonFactory = nullptr);

    ~JoynrClusterControllerRuntime() override;

    void start();
    void stop();
    void shutdown() final;
    void runForever();

    // Implement IClusterControllerSignalHandler
    void startExternalCommunication() final;
    void stopExternalCommunication() final;
    void shutdownClusterController() final;

    void init();

    /*
     * Inject predefined capabilities stored in a JSON file.
     */
    void injectGlobalCapabilitiesFromFile(const std::string& fileName);

protected:
    void importMessageRouterFromFile();
    void importPersistedLocalCapabilitiesDirectory();

    std::map<std::string, joynr::types::DiscoveryEntryWithMetaInfo> getProvisionedEntries()
            const final;

    std::shared_ptr<IMessageRouter> getMessageRouter() final;

    std::shared_ptr<IDispatcher> _joynrDispatcher;

    std::shared_ptr<SubscriptionManager> _subscriptionManager;
    std::shared_ptr<IMessageSender> _messageSender;

    std::shared_ptr<GlobalCapabilitiesDirectoryClient> _globalCapabilitiesDirectoryClient;
    std::shared_ptr<LocalCapabilitiesDirectory> _localCapabilitiesDirectory;

    std::shared_ptr<InProcessMessagingSkeleton> _libJoynrMessagingSkeleton;

    MqttMessagingSkeletonFactory _mqttMessagingSkeletonFactory;

    std::vector<std::shared_ptr<JoynrClusterControllerMqttConnectionData>>
            _mqttConnectionDataVector;

    std::vector<std::shared_ptr<IDispatcher>> _dispatcherList;

    std::unique_ptr<Settings> _settings;
    LibjoynrSettings _libjoynrSettings;
    std::shared_ptr<LocalDomainAccessController> _localDomainAccessController;
    ClusterControllerSettings _clusterControllerSettings;

    // skeleton and stub-factory register methods to the server, hence the server must be removed
    // first
    UdsSettings _udsSettings;
    std::shared_ptr<UdsMessagingStubFactory> _udsMessagingStubFactory;
    std::unique_ptr<UdsCcMessagingSkeleton> _udsCcMessagingSkeleton;
    std::unique_ptr<UdsServer> _udsServer;

    WebSocketSettings _wsSettings;
    std::shared_ptr<IWebsocketCcMessagingSkeleton> _wsCcMessagingSkeleton;
    std::shared_ptr<IWebsocketCcMessagingSkeleton> _wsTLSCcMessagingSkeleton;
    bool _mqttMessagingIsRunning;
    bool _doMqttMessaging;
    std::shared_ptr<WebSocketMessagingStubFactory> _wsMessagingStubFactory;

    ADD_LOGGER(JoynrClusterControllerRuntime)

private:
    template <typename T>
    std::string registerInternalSystemServiceProvider(std::shared_ptr<T> provider,
                                                      const std::string& participantId)
    {
        const std::string domain(_systemServicesSettings.getDomain());
        const std::string interfaceName(T::INTERFACE_NAME());

        _participantIdStorage->setProviderParticipantId(
                domain, interfaceName, T::MAJOR_VERSION, participantId);

        joynr::types::ProviderQos systemProviderQos;
        std::vector<joynr::types::CustomParameter> customParameters;
        customParameters.push_back(joynr::types::CustomParameter(
                std::string("___CC.InternalProvider___"), std::string("true")));
        systemProviderQos.setCustomParameters(customParameters);
        systemProviderQos.setPriority(1);
        systemProviderQos.setScope(joynr::types::ProviderScope::LOCAL);
        systemProviderQos.setSupportsOnChangeSubscriptions(false);

        return registerProvider(domain, provider, systemProviderQos);
    }

    void registerInternalSystemServiceProviders();
    void unregisterInternalSystemServiceProviders();
    void unregisterInternalSystemServiceProvider(const std::string& participantId);
    void startLocalCommunication();
    std::string getSerializedGlobalClusterControllerAddress() const;
    const system::RoutingTypes::Address& getGlobalClusterControllerAddress() const;
    void scheduleRemoveStaleTimer();
    void sendScheduledRemoveStale(const boost::system::error_code& timerError);

    std::shared_ptr<MulticastMessagingSkeletonDirectory> _multicastMessagingSkeletonDirectory;

    std::shared_ptr<CcMessageRouter> _ccMessageRouter;
    std::shared_ptr<AccessControlListEditor> _aclEditor;

    void enableAccessController(
            const std::map<std::string, types::DiscoveryEntryWithMetaInfo>& provisionedEntries);
    friend class ::JoynrClusterControllerRuntimeTest;

    Semaphore _lifetimeSemaphore;

    std::shared_ptr<joynr::AccessController> _accessController;
    std::shared_ptr<LocalCapabilitiesDirectoryStore> _localCapabilitiesDirectoryStore;

    std::string _routingProviderParticipantId;
    std::string _discoveryProviderParticipantId;
    std::string _providerReregistrationControllerParticipantId;
    std::string _messageNotificationProviderParticipantId;
    std::string _accessControlListEditorProviderParticipantId;
    bool _isShuttingDown;
    const system::RoutingTypes::Address _dummyGlobalAddress;
    const std::int64_t _clusterControllerStartDateMs;

    std::vector<std::string> _availableGbids;
    void fillAvailableGbidsVector();

    static constexpr std::int64_t _defaultRemoveStaleDelayMs{300000};
    const std::int64_t _removeStaleDelay;
    boost::asio::steady_timer _removeStaleTimer;
};

} // namespace joynr
#endif // JOYNRCLUSTERCONTROLLERRUNTIME_H
