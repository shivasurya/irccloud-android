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

package com.irccloud.android.fragment;



import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import com.fasterxml.jackson.databind.JsonNode;
import com.irccloud.android.ColorFormatter;
import com.irccloud.android.IRCCloudApplication;
import com.irccloud.android.IRCCloudJSONObject;
import com.irccloud.android.IRCCloudLinkMovementMethod;
import com.irccloud.android.R;
import com.irccloud.android.data.collection.ServersList;

public class WhoisFragment extends DialogFragment {
    IRCCloudJSONObject event;
    TextView extra, name, mask, server, time, timeTitle, channels, channelsTitle, opChannels, opTitle,
            operChannels, operTitle, ownerChannels, ownerTitle, adminChannels, adminTitle, halfopChannels, halfopTitle, voicedChannels, voicedTitle, awayTitle, away;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context ctx = getActivity();

        if (ctx == null)
            return null;

        LayoutInflater inflater = (LayoutInflater) ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(R.layout.dialog_whois, null);
        extra = v.findViewById(R.id.extra);
        name = v.findViewById(R.id.name);
        mask = v.findViewById(R.id.mask);
        server = v.findViewById(R.id.server);
        timeTitle = v.findViewById(R.id.timeTitle);
        time = v.findViewById(R.id.time);
        awayTitle = v.findViewById(R.id.awayTitle);
        away = v.findViewById(R.id.away);
        operTitle = v.findViewById(R.id.operTitle);
        operChannels = v.findViewById(R.id.operChannels);
        operChannels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        operChannels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        ownerTitle = v.findViewById(R.id.ownerTitle);
        ownerChannels = v.findViewById(R.id.ownerChannels);
        ownerChannels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        ownerChannels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        adminTitle = v.findViewById(R.id.adminTitle);
        adminChannels = v.findViewById(R.id.adminChannels);
        adminChannels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        adminChannels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        opTitle = v.findViewById(R.id.opTitle);
        opChannels = v.findViewById(R.id.opChannels);
        opChannels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        opChannels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        halfopTitle = v.findViewById(R.id.halfopTitle);
        halfopChannels = v.findViewById(R.id.halfopChannels);
        halfopChannels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        halfopChannels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        voicedTitle = v.findViewById(R.id.voicedTitle);
        voicedChannels = v.findViewById(R.id.voicedChannels);
        voicedChannels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        voicedChannels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        channelsTitle = v.findViewById(R.id.channelsTitle);
        channels = v.findViewById(R.id.channels);
        channels.setMovementMethod(IRCCloudLinkMovementMethod.getInstance());
        channels.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });
        if (savedInstanceState != null && savedInstanceState.containsKey("event")) {
            event = new IRCCloudJSONObject(savedInstanceState.getString("event"));
        }
        Dialog d = new AlertDialog.Builder(ctx)
                .setTitle("WHOIS response for " + event.getString("user_nick"))
                .setView(v)
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
        return d;
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        state.putString("event", event.toString());
    }

    @Override
    public void setArguments(Bundle args) {
        event = new IRCCloudJSONObject(args.getString("event"));
    }

    private String format_duration(long duration) {
        if (duration > (86400 * 30)) {
            return ((int) (duration / (86400 * 30))) + " months";
        } else if (duration > 86400) {
            return ((int) (duration / 86400)) + " days";
        } else if (duration > 3600) {
            return ((int) (duration / 3600)) + " hours";
        } else if (duration > 60) {
            return ((int) (duration / 60)) + " minutes";
        } else {
            return duration + " seconds";
        }
    }

    public void onResume() {
        super.onResume();

        if (event != null) {
            String nick = event.getString("user_nick");
            String extratxt = "";
            if (event.has("op_nick")) {
                extratxt += nick + " " + event.getString("op_msg") + "\n\n";
            }
            if (event.has("opername")) {
                extratxt += nick + " " + event.getString("opername_msg") + " " + event.getString("opername") + "\n\n";
            }
            if (event.has("stats_dline")) {
                extratxt += nick + " " + event.getString("stats_dline") + "\n\n";
            }
            if (event.has("userip")) {
                extratxt += nick + " " + event.getString("userip") + "\n\n";
            }
            if (event.has("host")) {
                extratxt += nick + " " + event.getString("host") + "\n\n";
            }
            if (event.has("bot_msg")) {
                extratxt += nick + " " + event.getString("bot_msg") + "\n\n";
            }
            if (event.has("cgi")) {
                extratxt += nick + " " + event.getString("cgi") + "\n\n";
            }
            if (event.has("help")) {
                extratxt += nick + " " + event.getString("help") + "\n\n";
            }
            if (event.has("vworld")) {
                extratxt += nick + " " + event.getString("vworld") + "\n\n";
            }
            if (event.has("modes")) {
                extratxt += nick + " " + event.getString("modes") + "\n\n";
            }
            if (event.has("client_cert")) {
                extratxt += nick + " " + event.getString("client_cert") + "\n\n";
            }
            if (event.has("secure")) {
                extratxt += nick + " " + event.getString("secure") + "\n\n";
            }
            if (extratxt.length() > 0) {
                extra.setVisibility(View.VISIBLE);
                extra.setText(extratxt.substring(0, extratxt.length() - 2));
            } else {
                extra.setVisibility(View.GONE);
            }
            if (event.has("user_realname")) {
                String nametxt = event.getString("user_realname");
                if (event.has("user_logged_in_as") && event.getString("user_logged_in_as").length() > 0) {
                    nametxt += "\u000F (authed as " + event.getString("user_logged_in_as") + ")";
                }
                name.setText(ColorFormatter.html_to_spanned(ColorFormatter.emojify(ColorFormatter.irc_to_html(TextUtils.htmlEncode(nametxt)))));
            } else {
                name.setText("");
            }
            mask.setText(ColorFormatter.html_to_spanned(ColorFormatter.irc_to_html(TextUtils.htmlEncode(event.getString("user_username"))) + "@" + ColorFormatter.irc_to_html(TextUtils.htmlEncode(event.getString("user_host")))));
            if (event.has("actual_host"))
                mask.setText(mask.getText() + "/" + event.getString("actual_host"));
            String s = event.getString("server_addr");
            if (event.has("server_extra") && event.getString("server_extra").length() > 0)
                s += " (" + event.getString("server_extra") + ")";
            server.setText(s);
            if (event.has("signon_time")) {
                String timetxt = format_duration((System.currentTimeMillis() / 1000L) - event.getLong("signon_time"));
                if (event.has("idle_secs") && event.getLong("idle_secs") > 0)
                    timetxt += " (idle for " + format_duration(event.getLong("idle_secs")) + ")";
                time.setText(timetxt);
                timeTitle.setVisibility(View.VISIBLE);
                time.setVisibility(View.VISIBLE);
            } else {
                timeTitle.setVisibility(View.GONE);
                time.setVisibility(View.GONE);
            }
            if (event.has("away") && event.getString("away").length() > 0) {
                away.setText(ColorFormatter.html_to_spanned(ColorFormatter.emojify(ColorFormatter.irc_to_html(TextUtils.htmlEncode(event.getString("away"))))));
                awayTitle.setVisibility(View.VISIBLE);
                away.setVisibility(View.VISIBLE);
            } else {
                awayTitle.setVisibility(View.GONE);
                away.setVisibility(View.GONE);
            }
            buildChannelList("channels_oper", operTitle, operChannels);
            buildChannelList("channels_owner", ownerTitle, ownerChannels);
            buildChannelList("channels_admin", adminTitle, adminChannels);
            buildChannelList("channels_op", opTitle, opChannels);
            buildChannelList("channels_halfop", halfopTitle, halfopChannels);
            buildChannelList("channels_voiced", voicedTitle, voicedChannels);
            buildChannelList("channels_member", channelsTitle, channels);
        }
    }

    private void buildChannelList(String field, TextView title, TextView channels) {
        if (event.has(field)) {
            title.setVisibility(View.VISIBLE);
            channels.setVisibility(View.VISIBLE);
            String channelstxt = "";
            JsonNode c = event.getJsonNode(field);
            for (int i = 0; i < c.size(); i++) {
                String chan = c.get(i).asText();
                channelstxt += "• " + chan + "<br/>";
            }
            channels.setText(ColorFormatter.html_to_spanned(channelstxt, true, ServersList.getInstance().getServer(event.cid())));
        } else {
            title.setVisibility(View.GONE);
            channels.setVisibility(View.GONE);
        }
    }
}
