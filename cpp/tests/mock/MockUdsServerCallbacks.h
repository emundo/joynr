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
#ifndef TESTS_MOCK_MOCKUDSSERVERCALLBACK_H
#define TESTS_MOCK_MOCKUDSSERVERCALLBACK_H

#include <utility>

#include <gmock/gmock.h>

#include "smrf/ByteVector.h"

#include "joynr/exceptions/JoynrException.h"
#include "joynr/system/RoutingTypes/UdsClientAddress.h"

class UdsServerCallbackInterface
{
public:
    virtual void connected(const joynr::system::RoutingTypes::UdsClientAddress&,
                           std::shared_ptr<joynr::IUdsSender>) = 0;
    virtual void disconnected(const joynr::system::RoutingTypes::UdsClientAddress&) = 0;
    virtual void receivedMock(const joynr::system::RoutingTypes::UdsClientAddress&,
                              smrf::ByteVector) = 0;
    void received(const joynr::system::RoutingTypes::UdsClientAddress& id, smrf::ByteVector&& msg)
    {
        receivedMock(id, std::move(msg));
    }
    virtual void sendFailed(const joynr::exceptions::JoynrRuntimeException&) = 0;
};

class MockUdsServerCallbacks : public UdsServerCallbackInterface
{
public:
    MOCK_METHOD2(connected,
                 void(const joynr::system::RoutingTypes::UdsClientAddress&,
                      std::shared_ptr<joynr::IUdsSender>));
    MOCK_METHOD1(disconnected, void(const joynr::system::RoutingTypes::UdsClientAddress&));
    MOCK_METHOD2(receivedMock,
                 void(const joynr::system::RoutingTypes::UdsClientAddress&, smrf::ByteVector));
    MOCK_METHOD1(sendFailed, void(const joynr::exceptions::JoynrRuntimeException&));
};

#endif // TESTS_MOCK_MOCKUDSSERVERCALLBACK_H