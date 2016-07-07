/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
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
#include <gtest/gtest.h>
#include <gmock/gmock.h>
#include <string>
#include "joynr/MessageRouter.h"
#include "joynr/JoynrMessage.h"
#include "joynr/Dispatcher.h"
#include "joynr/SubscriptionCallback.h"
#include <string>
#include <vector>
#include "joynr/JoynrMessageFactory.h"
#include "joynr/JoynrMessageSender.h"
#include "joynr/Dispatcher.h"
#include "joynr/Request.h"
#include "joynr/Reply.h"
#include "tests/utils/MockObjects.h"
#include "utils/MockCallback.h"
#include "joynr/InterfaceRegistrar.h"

#include "joynr/tests/Itest.h"
#include "joynr/tests/testRequestInterpreter.h"
#include "joynr/types/Localisation/GpsLocation.h"
#include "joynr/MetaTypeRegistrar.h"
#include "joynr/SingleThreadedIOService.h"

using namespace ::testing;
using namespace joynr;


class DispatcherTest : public ::testing::Test {
public:
    DispatcherTest() :
        singleThreadIOService(),
        mockMessageRouter(new MockMessageRouter(singleThreadIOService.getIOService())),
        mockCallback(new MockCallbackWithJoynrException<types::Localisation::GpsLocation>()),
        mockRequestCaller(new MockTestRequestCaller()),
        mockReplyCaller(new MockReplyCaller<types::Localisation::GpsLocation>(
                [this] (const joynr::types::Localisation::GpsLocation& location) {
                    mockCallback->onSuccess(location);
                },
                [] (const exceptions::JoynrException&) {
                })),
        mockSubscriptionListener(new MockSubscriptionListenerOneType<types::Localisation::GpsLocation>()),
        gpsLocation1(1.1, 2.2, 3.3, types::Localisation::GpsFixEnum::MODE2D, 0.0, 0.0, 0.0, 0.0, 444, 444, 444),
        qos(2000),
        providerParticipantId("TEST-providerParticipantId"),
        proxyParticipantId("TEST-proxyParticipantId"),
        requestReplyId("TEST-requestReplyId"),
        messageFactory(),
        messageSender(mockMessageRouter),
        dispatcher(&messageSender, singleThreadIOService.getIOService())
    {
        InterfaceRegistrar::instance().registerRequestInterpreter<tests::testRequestInterpreter>(tests::ItestBase::INTERFACE_NAME());
    }


    void SetUp(){
    }

    void TearDown(){
    }

    void invokeOnSuccessWithGpsLocation(
            std::function<void(const joynr::types::Localisation::GpsLocation& location)> onSuccess,
            std::function<void(const joynr::exceptions::ProviderRuntimeException&)> onError) {
        std::ignore = onError;
        onSuccess(gpsLocation1);
    }

protected:
    ADD_LOGGER(DispatcherTest);
    SingleThreadedIOService singleThreadIOService;
    std::shared_ptr<MockMessageRouter> mockMessageRouter;
    std::shared_ptr<MockCallbackWithJoynrException<types::Localisation::GpsLocation> > mockCallback;

    std::shared_ptr<MockTestRequestCaller> mockRequestCaller;
    std::shared_ptr<MockReplyCaller<types::Localisation::GpsLocation> > mockReplyCaller;
    std::shared_ptr<MockSubscriptionListenerOneType<types::Localisation::GpsLocation> > mockSubscriptionListener;

    types::Localisation::GpsLocation gpsLocation1;

    // create test data
    MessagingQos qos;
    std::string providerParticipantId;
    std::string proxyParticipantId;
    std::string requestReplyId;

    JoynrMessageFactory messageFactory;
    JoynrMessageSender messageSender;
    Dispatcher dispatcher;
};

INIT_LOGGER(DispatcherTest);

// from JoynrDispatcher.receive(Request) to IRequestCaller.operation(params)
// this test goes a step further and makes sure that the response is visible in Messaging
TEST_F(DispatcherTest, receive_interpreteRequestAndCallOperation) {

    // Expect the mock object MockGpsRequestCaller in MockObjects.h to be called.
    // The OUT param Gpslocation is set with gpsLocation1
    EXPECT_CALL(
                *mockRequestCaller,
                getLocation(
                    A<std::function<void(const joynr::types::Localisation::GpsLocation&)>>(),
                    A<std::function<void(const joynr::exceptions::ProviderRuntimeException&)>>()
                )
    ).WillOnce(Invoke(this, &DispatcherTest::invokeOnSuccessWithGpsLocation));

    qos.setTtl(1000);
    // build request for location from mock Gps Provider
    Request request;
    request.setRequestReplyId(requestReplyId);
    request.setMethodName("getLocation");
    request.setParams(std::vector<Variant>());
    request.setParamDatatypes(std::vector<std::string>());


    JoynrMessage msg = messageFactory.createRequest(
                proxyParticipantId,
                providerParticipantId,
                qos,
                request
    );

    // construct the result we expect in messaging.transmit. The JoynrMessage
    // contains a serialized version of the response with the gps location.
    std::vector<Variant> value;

    value.push_back(Variant::make<types::Localisation::GpsLocation>(gpsLocation1));
    Reply reply;
    reply.setResponse(std::move(value));
    reply.setRequestReplyId(requestReplyId);
    JoynrMessage expectedReply = messageFactory.createReply(
                proxyParticipantId,
                providerParticipantId,
                qos,
                reply
    );

    JOYNR_LOG_DEBUG(logger, "expectedReply.payload()={}",expectedReply.getPayload());
    // setup MockMessaging to expect the response
    EXPECT_CALL(
                *mockMessageRouter,
                route(
                    AllOf(
                        Property(&JoynrMessage::getType, Eq(JoynrMessage::VALUE_MESSAGE_TYPE_REPLY)),
                        Property(&JoynrMessage::getPayload, Eq(expectedReply.getPayload()))
                    ),
                    _
                )
    );

    // test code: send the request through the dispatcher.
    // This should cause our mock messaging to receive a reply from the mock provider
    dispatcher.addRequestCaller(providerParticipantId, mockRequestCaller);

    dispatcher.receive(msg);
    std::this_thread::sleep_for(std::chrono::milliseconds(250));
}

TEST_F(DispatcherTest, receive_interpreteReplyAndCallReplyCaller) {

    // Expect the mock callback's onSuccess method to be called with the reply (a gps location)
    EXPECT_CALL(*mockCallback, onSuccess(Eq(gpsLocation1)));

    // getType is used by the ReplyInterpreterFactory to create an interpreter for the reply
    // so this has to match with the type being passed to the dispatcher in the reply
    ON_CALL(*mockReplyCaller, getType()).WillByDefault(Return(std::string("types::Localisation::GpsLocation")));

    //construct a reply containing a GpsLocation
    Reply reply;
    reply.setRequestReplyId(requestReplyId);
    std::vector<Variant> response;
    response.push_back(Variant::make<types::Localisation::GpsLocation>(gpsLocation1));
    reply.setResponse(std::move(response));

    JoynrMessage msg = messageFactory.createReply(
                proxyParticipantId,
                providerParticipantId,
                qos,
                reply
    );


    // test code: send the reply through the dispatcher.
    // This should cause our reply caller to be called
    dispatcher.addReplyCaller(requestReplyId, mockReplyCaller, qos);
    dispatcher.receive(msg);

    std::this_thread::sleep_for(std::chrono::milliseconds(250));
}
