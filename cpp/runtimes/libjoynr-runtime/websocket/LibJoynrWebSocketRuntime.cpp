/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2014 BMW Car IT GmbH
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
#include "runtimes/libjoynr-runtime/websocket/LibJoynrWebSocketRuntime.h"

#include <QtCore/QObject>

#include "libjoynr/websocket/WebSocketMessagingStubFactory.h"
#include "joynr/system/RoutingTypes/WebSocketClientAddress.h"
#include "libjoynr/websocket/WebSocketLibJoynrMessagingSkeleton.h"
#include "libjoynr/websocket/WebSocketClient.h"
#include "joynr/Util.h"
#include "joynr/TypeUtil.h"
#include "joynr/JsonSerializer.h"

namespace joynr
{

joynr_logging::Logger* LibJoynrWebSocketRuntime::logger =
        joynr_logging::Logging::getInstance()->getLogger("MSG", "LibJoynrWebSocketRuntime");

LibJoynrWebSocketRuntime::LibJoynrWebSocketRuntime(Settings* settings)
        : LibJoynrRuntime(settings),
          wsSettings(*settings),
          wsLibJoynrMessagingSkeleton(nullptr),
          websocket(new joynr::WebSocketClient(
                  [this](const std::string& err) { this->onWebSocketError(err); },
                  [](joynr::WebSocket*) {}))
{
    std::string uuid = Util::createUuid();
    // remove dashes
    uuid.erase(std::remove(uuid.begin(), uuid.end(), '-'), uuid.end());
    std::string libjoynrMessagingId = "libjoynr.messaging.participantid_" + uuid;
    std::shared_ptr<joynr::system::RoutingTypes::WebSocketClientAddress> libjoynrMessagingAddress(
            new system::RoutingTypes::WebSocketClientAddress(libjoynrMessagingId));

    // create connection to parent routing service
    std::shared_ptr<joynr::system::RoutingTypes::WebSocketAddress> ccMessagingAddress(
            new joynr::system::RoutingTypes::WebSocketAddress(
                    wsSettings.createClusterControllerMessagingAddress()));

    websocket->connect(*ccMessagingAddress);

    // send intialization message containing libjoynr messaging address
    std::string initializationMsg(JsonSerializer::serialize(*libjoynrMessagingAddress).data());
    LOG_TRACE(logger,
              FormatString("OUTGOING sending websocket intialization message\nmessage: %1\nto: %2")
                      .arg(initializationMsg)
                      .arg(libjoynrMessagingAddress->toString())
                      .str());
    websocket->send(initializationMsg);

    WebSocketMessagingStubFactory* factory = new WebSocketMessagingStubFactory();
    factory->addServer(*ccMessagingAddress, websocket.get());

    LibJoynrRuntime::init(factory, libjoynrMessagingAddress, ccMessagingAddress);
}

LibJoynrWebSocketRuntime::~LibJoynrWebSocketRuntime()
{
    delete wsLibJoynrMessagingSkeleton;
    wsLibJoynrMessagingSkeleton = nullptr;
}

void LibJoynrWebSocketRuntime::startLibJoynrMessagingSkeleton(MessageRouter& messageRouter)
{
    // create messaging skeleton using uuid
    wsLibJoynrMessagingSkeleton = new WebSocketLibJoynrMessagingSkeleton(messageRouter);
    websocket->registerReceiveCallback([&](const std::string& msg) {
        wsLibJoynrMessagingSkeleton->onTextMessageReceived(msg);
    });
}

void LibJoynrWebSocketRuntime::onWebSocketError(const std::string& errorMessage)
{
    LOG_ERROR(logger, FormatString("WebSocket error occurred: %1").arg(errorMessage).str());
}

} // namespace joynr
