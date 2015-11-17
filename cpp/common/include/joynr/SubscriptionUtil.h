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
#ifndef SUBSCRIPTIONUTIL_H
#define SUBSCRIPTIONUTIL_H

#include "joynr/QtSubscriptionQos.h"
#include "joynr/JoynrCommonExport.h"

namespace joynr
{

class JOYNRCOMMON_EXPORT SubscriptionUtil
{
public:
    static bool isOnChangeSubscription(QtSubscriptionQos* qos);
    static bool isOnChangeSubscription(const Variant& qos);
    static qint64 getAlertInterval(QtSubscriptionQos* qos);
    static int64_t getAlertInterval(const Variant& qos);
    static qint64 getMinInterval(QtSubscriptionQos* qos);
    static int64_t getMinInterval(const Variant& qos);
    static qint64 getPeriodicPublicationInterval(QtSubscriptionQos* qos);
    static int64_t getPeriodicPublicationInterval(const Variant& qos);
    static Variant getVariant(const SubscriptionQos& qos);
};
}
#endif // SUBSCRIPTIONUTIL_H
