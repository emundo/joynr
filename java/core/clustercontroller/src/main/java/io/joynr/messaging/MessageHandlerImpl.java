package io.joynr.messaging;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2015 BMW Car IT GmbH
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

import static joynr.JoynrMessage.MESSAGE_TYPE_BROADCAST_SUBSCRIPTION_REQUEST;
import static joynr.JoynrMessage.MESSAGE_TYPE_REQUEST;
import static joynr.JoynrMessage.MESSAGE_TYPE_SUBSCRIPTION_REQUEST;

import io.joynr.exceptions.JoynrDelayMessageException;
import io.joynr.exceptions.JoynrMessageNotSentException;
import io.joynr.exceptions.JoynrSendBufferFullException;
import io.joynr.exceptions.JoynrShutdownException;

import java.io.IOException;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;

import joynr.JoynrMessage;
import joynr.system.RoutingTypes.Address;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 * Repeatedly tries to send a message until successful sending or the time to live has expired.
 */

public class MessageHandlerImpl implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandlerImpl.class);
    private static final DateFormat DateFormatter = new SimpleDateFormat("dd/MM HH:mm:ss:sss");
    public static final int THREADPOOLSIZE = 4;

    private final MessagingSettings settings;
    private MessageScheduler sendRequestScheduler;

    private final String ownChannelId;

    private final ObjectMapper objectMapper;

    @Inject
    public MessageHandlerImpl(MessageScheduler sendRequestScheduler,
                              @Named(MessagingPropertyKeys.CHANNELID) String ownChannelId,
                              MessagingSettings settings,
                              ObjectMapper objectMapper) {
        this.sendRequestScheduler = sendRequestScheduler;
        this.ownChannelId = ownChannelId;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    /* (non-Javadoc)
     * @see io.joynr.messaging.MessageHandler#sendMessage(java.lang.String, joynr.JoynrMessage)
     */
    @Override
    public void sendMessage(final Address address, final JoynrMessage message) throws JoynrSendBufferFullException,
                                                                              JoynrMessageNotSentException,
                                                                              JsonGenerationException,
                                                                              JsonMappingException, IOException {

        if (message.getType().equals(MESSAGE_TYPE_REQUEST)
                || message.getType().equals(MESSAGE_TYPE_SUBSCRIPTION_REQUEST)
                || message.getType().equals(MESSAGE_TYPE_BROADCAST_SUBSCRIPTION_REQUEST)) {
            message.setReplyTo(getReplyToChannelId());
        }

        long currentTimeMillis = System.currentTimeMillis();
        long ttlExpirationDate_ms = message.getExpiryDate();

        if (ttlExpirationDate_ms <= currentTimeMillis) {
            String errorMessage = MessageFormat.format("ttl must be greater than 0 / ttl timestamp must be in the future: now: {0} abs_ttl: {1}",
                                                       currentTimeMillis,
                                                       ttlExpirationDate_ms);
            logger.error(errorMessage);
            throw new JoynrMessageNotSentException(errorMessage);
        }

        final MessageContainer messageContainer = new MessageContainer(address,
                                                                       message,
                                                                       ttlExpirationDate_ms,
                                                                       objectMapper);

        final FailureAction failureAction = new FailureAction() {
            @Override
            public void execute(Throwable error) {
                if (error instanceof JoynrShutdownException) {
                    logger.warn("{}", error.getMessage());
                    return;
                }
                logger.error("!!!! ERROR SENDING: messageId: {} to Address: {}. Error: {}", new String[]{
                        message.getId(), address.toString(), error.getMessage() });

                messageContainer.incrementRetries();
                long delay_ms;
                if (error instanceof JoynrDelayMessageException) {
                    delay_ms = ((JoynrDelayMessageException) error).getDelayMs();
                } else {
                    delay_ms = settings.getSendMsgRetryIntervalMs();
                    delay_ms += exponentialBackoff(delay_ms, messageContainer.getRetries());
                }

                try {
                    logger.error("Rescheduling messageId: {} with delay " + delay_ms
                                         + " ms, new TTL expiration date: {}",
                                 messageContainer.getMessageId(),
                                 DateFormatter.format(messageContainer.getExpiryDate()));
                    sendRequestScheduler.scheduleMessage(messageContainer, delay_ms, this);
                    return;
                } catch (JoynrSendBufferFullException e) {
                    try {
                        logger.error("Rescheduling message: {} delayed {} ms because send buffer is full",
                                     delay_ms,
                                     messageContainer.getMessageId());
                        Thread.sleep(delay_ms);
                        this.execute(e);
                    } catch (InterruptedException e1) {
                        return;
                    }
                }
            }
        };
        // try to schedule a new message once, if the buffer is full, an
        // exception is thrown

        logger.trace("SEND messageId: {} from: {} to: {} scheduleRequest", new Object[]{ message.getId(),
                message.getHeaderValue(JoynrMessage.HEADER_NAME_FROM_PARTICIPANT_ID),
                message.getHeaderValue(JoynrMessage.HEADER_NAME_TO_PARTICIPANT_ID) });
        sendRequestScheduler.scheduleMessage(messageContainer, 0, failureAction);
    }

    /* (non-Javadoc)
     * @see io.joynr.messaging.MessageHandler#shutdown()
     */
    @Override
    public void shutdown() {
        try {
            sendRequestScheduler.shutdown();
        } catch (Throwable e) {
            logger.error("Exception caught while shutting down");
        }
    }

    /* (non-Javadoc)
     * @see io.joynr.messaging.MessageHandler#getReplyToChannelId()
     */
    @Override
    public String getReplyToChannelId() {
        // TODO replace ownChannelId with the reply to channelId to support multiple channels
        return ownChannelId;
    }

    private long exponentialBackoff(long delay_ms, int retries) {
        logger.debug("TRIES: " + retries);
        long millis = delay_ms + (long) ((2 ^ (retries)) * delay_ms * Math.random());
        logger.debug("MILLIS: " + millis);
        return millis;
    }
}
