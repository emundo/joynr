package joynr;

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

import io.joynr.exceptions.JoynrException;

import java.util.Arrays;
import java.util.List;

/**
 * Value class for the response of a JSON-RPC function call.
 */
public class Reply implements JoynrMessageType {
    private List<?> response;
    private JoynrException error;
    private String requestReplyId;

    public Reply() {
    }

    public Reply(String requestReplyId, Object... response) {
        this.requestReplyId = requestReplyId;
        this.response = Arrays.asList(response);
        this.error = null;
    }

    public Reply(String requestReplyId, JoynrException error) {
        this.requestReplyId = requestReplyId;
        this.error = error;
        this.response = null;
    }

    public List<?> getResponse() {
        return response;
    }

    public JoynrException getError() {
        return error;
    }

    public String getRequestReplyId() {
        return requestReplyId;
    }

    @Override
    public String toString() {
        return "Reply: " + "requestReplyId: " + requestReplyId + (response == null ? "" : ", response: " + response)
                + (error == null ? "" : ", error: " + error);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Reply other = (Reply) obj;
        if (error == null) {
            if (other.error != null) {
                return false;
            }
        } else if (!error.equals(other.error)) {
            return false;
        }
        if (requestReplyId == null) {
            if (other.requestReplyId != null) {
                return false;
            }
        } else if (!requestReplyId.equals(other.requestReplyId)) {
            return false;
        }
        if (response == null) {
            if (other.response != null) {
                return false;
            }
        } else if (!response.equals(other.response)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((error == null) ? 0 : error.hashCode());
        result = prime * result + ((requestReplyId == null) ? 0 : requestReplyId.hashCode());
        result = prime * result + ((response == null) ? 0 : response.hashCode());
        return result;
    }
}
