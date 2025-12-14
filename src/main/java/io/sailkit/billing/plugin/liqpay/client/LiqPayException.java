/*
 * Copyright 2024 Sailkit.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sailkit.billing.plugin.liqpay.client;

/**
 * Exception thrown when LiqPay API call fails.
 */
public class LiqPayException extends Exception {

    private final String errorCode;
    private final LiqPayResponse response;

    public LiqPayException(String message) {
        super(message);
        this.errorCode = null;
        this.response = null;
    }

    public LiqPayException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.response = null;
    }

    public LiqPayException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.response = null;
    }

    public LiqPayException(String errorCode, String message, LiqPayResponse response) {
        super(message);
        this.errorCode = errorCode;
        this.response = response;
    }

    public LiqPayException(LiqPayResponse response) {
        super(response.getErrDescription() != null ? response.getErrDescription() : response.getStatus());
        this.errorCode = response.getErrCode();
        this.response = response;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public LiqPayResponse getResponse() {
        return response;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LiqPayException{");
        if (errorCode != null) {
            sb.append("errorCode='").append(errorCode).append("', ");
        }
        sb.append("message='").append(getMessage()).append("'");
        if (response != null) {
            sb.append(", status='").append(response.getStatus()).append("'");
        }
        sb.append('}');
        return sb.toString();
    }
}
