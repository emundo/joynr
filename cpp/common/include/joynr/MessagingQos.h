/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2013 BMW Car IT GmbH
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

#ifndef MESSAGINGQOS_H
#define MESSAGINGQOS_H

#include <cstdint>
#include <string>
#include <unordered_map>
#include <iosfwd>
#include "joynr/JoynrCommonExport.h"
#include "joynr/MessagingQosEffort.h"

namespace joynr
{

/**
  * @brief Class for messaging quality of service settings
  */
class JOYNRCOMMON_EXPORT MessagingQos
{
public:
    /**
     * @brief Base constructor
     * @param ttl The time to live in milliseconds
     * @param effort The effort to expend during message delivery
     */
    explicit MessagingQos(std::uint64_t ttl = 60000,
                          MessagingQosEffort::Enum effort = MessagingQosEffort::Enum::NORMAL);
    /** @brief Copy constructor */
    MessagingQos(const MessagingQos& other) = default;

    /** @brief Destructor */
    virtual ~MessagingQos() = default;

    /**
     * @brief Stringifies the class
     * @return stringified class content
     */
    virtual std::string toString() const;

    /**
     * @brief Gets the current time to live settings
     * @return time to live in milliseconds
     */
    std::uint64_t getTtl() const;

    /**
     * @brief Sets the time to live
     * @param ttl Time to live in milliseconds
     */
    void setTtl(const std::uint64_t& ttl);

    /**
     * @brief get the effort to expend during message delivery
     */
    MessagingQosEffort::Enum getEffort() const;

    /**
     * @brief set the effort to expend during message delivery
     * @param effort the new value for effort
     */
    void setEffort(const MessagingQosEffort::Enum effort);

    /**
     * @brief Puts a header value for the given header key, replacing an existing value
     * if necessary.
     * @param key the header key for which to put the value.
     * @param value the value to put for the given header key.
     */
    void putCustomMessageHeader(const std::string& key, const std::string& value);

    /**
     * @brief Puts all key/value pairs from the map into the header value map,
     * replacing existing values where necessary. This operation is purely additive.
     * @param values the key/value map to add to the map of custom header values.
     */
    void putAllCustomMessageHeaders(const std::unordered_map<std::string, std::string>& values);

    /**
     * @brief returns the current map of custom message headers.
     * @return the current map of custom message headers.
     */
    const std::unordered_map<std::string, std::string>& getCustomMessageHeaders() const;

    /** @brief assignment operator */
    MessagingQos& operator=(const MessagingQos& other) = default;
    /** @brief equality operator */
    bool operator==(const MessagingQos& other) const;

private:
    /** @brief The time to live in milliseconds */
    std::uint64_t ttl;

    /** @brief The effort to expend during message delivery */
    MessagingQosEffort::Enum effort;

    /** @brief The map of custom message headers */
    std::unordered_map<std::string, std::string> messageHeaders;

    /**
     * @brief Checks that the key and value of a custom message header contain
     * only valid characters and conform to the relevant patterns.
     * @param key may contain ascii alphanumeric or hyphen.
     * @param value may contain alphanumeric, space, semi-colon, colon, comma,
     * plus, ampersand, question mark, hyphen, dot, star, forward slash and back slash.
     */
    void checkCustomHeaderKeyValue(const std::string& key, const std::string& value) const;

    /**
      * @brief printing MessagingQos with google-test and google-mock
      * @param messagingQos the object to be printed
      * @param os the destination output stream the print should go into
      */
    friend void PrintTo(const MessagingQos& messagingQos, ::std::ostream* os);
};

// printing MessagingQos with google-test and google-mock
/**
 * @brief Print values of MessagingQos object
 * @param messagingQos The current object instance
 * @param os The output stream to send the output to
 */
void PrintTo(const MessagingQos& messagingQos, ::std::ostream* os);

} // namespace joynr
#endif // MESSAGINGQOS_H
