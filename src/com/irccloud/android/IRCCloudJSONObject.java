/*
 * Copyright (c) 2015 IRCCloud, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.irccloud.android;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.json.JSONObject;

import java.io.IOException;
import java.io.StringWriter;

public class IRCCloudJSONObject {
    private JsonNode o;
    private int cid = -1;
    private int bid = -1;
    private long eid = -1;
    private String type = null;

    public IRCCloudJSONObject() {
        o = new ObjectMapper().createObjectNode();
    }

    public IRCCloudJSONObject(JsonNode object) {
        o = object;
    }

    public IRCCloudJSONObject(String message) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            o = mapper.readValue(message, JsonNode.class);
        } catch (IOException e) {
            NetworkConnection.printStackTraceToCrashlytics(e);
        }
    }

    public IRCCloudJSONObject(JSONObject object) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            o = mapper.readValue(object.toString(), JsonNode.class);
        } catch (IOException e) {
            NetworkConnection.printStackTraceToCrashlytics(e);
        }
    }

    public int cid() {
        if (cid == -1)
            cid = getInt("cid");
        return cid;
    }

    public int bid() {
        if (bid == -1)
            bid = getInt("bid");
        return bid;
    }

    public long eid() {
        if (eid == -1)
            eid = getLong("eid");
        return eid;
    }

    public String type() {
        if (type == null) {
            if (o.has("type"))
                type = o.get("type").asText();
            else
                type = "undefined";
        }
        return type;
    }

    public boolean has(String name) {
        return o.has(name) && !o.get(name).isNull();
    }

    public boolean getBoolean(String name) {
        JsonNode node = o.get(name);
        if(node != null)
            return node.asBoolean(false);
        else
            return false;
    }

    public int getInt(String name) {
        JsonNode node = o.get(name);
        if(node != null)
            return node.asInt(-1);
        else
            return -1;
    }

    public long getLong(String name) {
        JsonNode node = o.get(name);
        if(node != null)
            return node.asLong(-1);
        else
            return -1;
    }

    public String getString(String name) {
        JsonNode node = o.get(name);
        if(node == null || node.isNull())
            return null;
        else
            return node.asText();
    }

    public JsonNode getJsonNode(String name) {
        return o.get(name);
    }

    public ObjectNode getJsonObject(String name) {
        try {
            return (ObjectNode) getJsonNode(name);
        } catch (ClassCastException e) {
            return null;
        }
    }

    public JsonNode getObject() {
        return o;
    }

    public String toString() {
        ObjectMapper mapper = new ObjectMapper();
        StringWriter writer = new StringWriter();
        try {
            mapper.writeValue(writer, o);
        } catch (IOException e) {
            NetworkConnection.printStackTraceToCrashlytics(e);
        }
        return writer.toString();
    }

    public void remove(String key) {
        if(o instanceof ObjectNode) {
            ((ObjectNode)o).remove(key);
        }
    }
}
