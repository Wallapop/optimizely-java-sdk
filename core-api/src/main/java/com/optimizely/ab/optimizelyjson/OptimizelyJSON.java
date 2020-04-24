/**
 *
 *    Copyright 2020, Optimizely and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.optimizelyjson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.optimizely.ab.Optimizely;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Map;

/**
 * OptimizelyJSON is an object for accessing values of JSON-type feature variables
 */
public class OptimizelyJSON {
    @Nullable
    private String payload;
    @Nullable
    private Map<String,Object> map;

    private static final Logger logger = LoggerFactory.getLogger(OptimizelyJSON.class);

    public OptimizelyJSON(@Nonnull String payload) {
        this.payload = payload;
    }

    public OptimizelyJSON(@Nonnull Map<String,Object> map) {
        this.map = map;
    }

    /**
     *  Returns the string representation of json data
     */
    public String toString() {
        if (payload == null) {
            if (map == null) return null;

            try {
                Gson gson = new Gson();
                payload = gson.toJson(map);
            } catch (JsonSyntaxException e) {
                logger.error("Provided dictionary could not be converted to string.");
            }
        }

        return payload;
    }

    /**
     *  Returns the Map<String,Object> representation of json data
     */
    public Map<String,Object> toMap() {
        if (map == null) {
            if (payload == null) return null;

            try {
                Gson gson = new Gson();
                map = gson.fromJson(payload, Map.class);
            } catch (JsonSyntaxException e) {
                logger.error("Provided string could not be converted to dictionary.");
            }
        }

        return map;
    }

    /**
     * Populates the schema passed by the user - it takes primitive types and complex struct type
     * <p>
     * Example:
     * <pre>
     *  JSON data is {"k1":true, "k2":{"k2":"v2"}}
     *
     *  Set jsonKey to "k1" to access the true boolean value or set it to to "k1.k2" to access {"k2":"v2"}.
     *  Set it to null to access the entire JSON data.
     * </pre>
     *
     * @param jsonKey The JSON key paths for the data to access
     * @param clazz The user-defined class that the json data will be parsed to
     * @return an instance of clazz type with the parsed data filled in (or null if parse fails)
     */
    public <T> T getValue(@Nullable String jsonKey, Class<T> clazz) {
        Map<String,Object> subMap = toMap();
        T result = null;

        if (jsonKey == null || jsonKey.isEmpty()) {
            return getValueInternal(subMap, clazz);
        }

        String[] keys = jsonKey.split("\\.", -1);   // -1 to keep trailing empty fields

        for(int i=0; i<keys.length; i++) {
            if (subMap == null) break;

            String key = keys[i];
            if (key.isEmpty()) break;

            if (i == keys.length - 1) {
                result = getValueInternal(subMap.get(key), clazz);
                break;
            }

            if (subMap.get(key) instanceof Map) {
                subMap = (Map<String, Object>) subMap.get(key);
            } else {
                logger.error("Value for JSON key ({}) not found.", jsonKey);
                break;
            }
        }

        if (result == null) {
            logger.error("Value for path could not be assigned to provided schema.");
        }
        return result;
    }

    private <T> T getValueInternal(@Nullable Object object, Class<T> clazz) {
        if (object == null) return null;

        if (clazz.isInstance(object)) return (T)object;  // primitive (String, Boolean, Integer, Double)

        try {
            Gson gson = new Gson();
            String payload = gson.toJson(object);
            return gson.fromJson(payload, clazz);
        } catch (Exception e) {
            //
        }

        return null;
    }

}

