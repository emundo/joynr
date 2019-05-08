/*
 * #%L
 * %%
 * Copyright (C) 2019 BMW Car IT GmbH
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
package io.joynr.messaging.mqtt;

import java.util.Set;

import io.joynr.messaging.AbstractMessagingSkeletonFactory;
import io.joynr.messaging.IMessagingSkeleton;
import io.joynr.messaging.JoynrMessageProcessor;
import io.joynr.messaging.RawMessagingPreprocessor;
import io.joynr.messaging.mqtt.statusmetrics.MqttStatusReceiver;
import io.joynr.messaging.routing.MessageRouter;
import joynr.system.RoutingTypes.MqttAddress;

public class MqttMessagingSkeletonFactory extends AbstractMessagingSkeletonFactory {

    // CHECKSTYLE IGNORE ParameterNumber FOR NEXT 1 LINES
    public MqttMessagingSkeletonFactory(String[] gbids,
                                        MqttAddress ownAddress,
                                        int maxIncomingMqttRequests,
                                        MessageRouter messageRouter,
                                        MqttClientFactory mqttClientFactory,
                                        MqttTopicPrefixProvider mqttTopicPrefixProvider,
                                        RawMessagingPreprocessor rawMessagingPreprocessor,
                                        Set<JoynrMessageProcessor> messageProcessors,
                                        MqttStatusReceiver mqttStatusReceiver) {
        super();
        IMessagingSkeleton messagingSkeleton = new MqttMessagingSkeleton(ownAddress,
                                                                         maxIncomingMqttRequests,
                                                                         messageRouter,
                                                                         mqttClientFactory,
                                                                         mqttTopicPrefixProvider,
                                                                         rawMessagingPreprocessor,
                                                                         messageProcessors,
                                                                         mqttStatusReceiver,
                                                                         gbids[0]);
        messagingSkeletonList.add(messagingSkeleton);
    }
}
