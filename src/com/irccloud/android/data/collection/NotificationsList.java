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

package com.irccloud.android.data.collection;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.WearableExtender;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.crashlytics.android.Crashlytics;
import com.irccloud.android.ColorFormatter;
import com.irccloud.android.DashClock;
import com.irccloud.android.IRCCloudApplication;
import com.irccloud.android.NetworkConnection;
import com.irccloud.android.R;
import com.irccloud.android.RemoteInputService;
import com.irccloud.android.SonyExtensionService;
import com.irccloud.android.activity.QuickReplyActivity;
import com.irccloud.android.data.IRCCloudDatabase;
import com.irccloud.android.data.model.Buffer;
import com.irccloud.android.data.model.Notification;
import com.irccloud.android.data.model.Notification_Table;
import com.irccloud.android.data.model.Notification_LastSeenEID;
import com.irccloud.android.data.model.Notification_LastSeenEID_Table;
import com.irccloud.android.data.model.Notification_ServerNick;
import com.irccloud.android.data.model.Notification_ServerNick_Table;
import com.irccloud.android.data.model.Server;
import com.raizlabs.android.dbflow.config.FlowManager;
import com.raizlabs.android.dbflow.sql.language.Delete;
import com.raizlabs.android.dbflow.sql.language.OperatorGroup;
import com.raizlabs.android.dbflow.sql.language.Select;
import com.raizlabs.android.dbflow.structure.database.transaction.FastStoreModelTransaction;
import com.sonyericsson.extras.liveware.extension.util.notification.NotificationUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class NotificationsList {
    private static NotificationsList instance = null;
    private int excludeBid = -1;
    private static final Timer mNotificationTimer = new Timer("notification-timer");
    private TimerTask mNotificationTimerTask = null;
    private final Object dbLock = new Object();

    public interface NotificationAddedListener {
        void onNotificationAdded(Notification notification);
    }

    public NotificationAddedListener notificationAddedListener = null;

    public static NotificationsList getInstance() {
        if (instance == null)
            instance = new NotificationsList();
        return instance;
    }

    public List<Notification> getNotifications() {
        synchronized (dbLock) {
            return new Select().from(Notification.class).where().orderBy(Notification_Table.bid, true).orderBy(Notification_Table.eid, true).queryList();
        }
    }

    public void clear() {
        try {
            for (Notification n : getNotifications()) {
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(n.bid);
            }
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
            try {
                if (PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).getBoolean("notify_sony", false))
                    NotificationUtil.deleteAllEvents(IRCCloudApplication.getInstance().getApplicationContext());
            } catch (Exception e) {
                //Sony LiveWare was probably removed
            }
        } catch (Exception e) {
            NetworkConnection.printStackTraceToCrashlytics(e);
        }
        updateTeslaUnreadCount();
        synchronized (dbLock) {
            Delete.table(Notification.class);
        }
    }

    public void clearLastSeenEIDs() {
        synchronized (dbLock) {
            Delete.table(Notification_LastSeenEID.class);
        }
    }

    public long getLastSeenEid(int bid) {
        Buffer b = BuffersList.getInstance().getBuffer(bid);
        if(b != null)
            return b.getLast_seen_eid();
        synchronized (dbLock) {
            Notification_LastSeenEID eid = new Select().from(Notification_LastSeenEID.class).where(Notification_LastSeenEID_Table.bid.is(bid)).querySingle();
            if (eid != null)
                return eid.eid;
            else
                return -1;
        }
    }

    public synchronized void updateLastSeenEid(int bid, long eid) {
        Notification_LastSeenEID n = new Notification_LastSeenEID();
        n.bid = bid;
        n.eid = eid;
        Buffer b = BuffersList.getInstance().getBuffer(bid);
        if(b != null) {
            FlowManager.getDatabase(IRCCloudDatabase.class).getTransactionManager().getSaveQueue().add(n);
        } else {
            synchronized (dbLock) {
                n.save();
            }
        }
    }

    public String getServerNick(int cid) {
        Server s = ServersList.getInstance().getServer(cid);
        if(s != null && s.getNick() != null && s.getNick().length() > 0)
            return s.getNick();
        synchronized (dbLock) {
            Notification_ServerNick nick = new Select().from(Notification_ServerNick.class).where(Notification_ServerNick_Table.cid.is(cid)).querySingle();
            if (nick != null)
                return nick.nick;
            else
                return null;
        }
    }

    public synchronized void updateServerNick(int cid, String nick) {
        Notification_ServerNick n = new Notification_ServerNick();
        n.cid = cid;
        n.nick = nick;
        Server s = ServersList.getInstance().getServer(cid);
        if(s != null) {
            FlowManager.getDatabase(IRCCloudDatabase.class).getTransactionManager().getSaveQueue().add(n);
        } else {
            synchronized (dbLock) {
                n.save();
            }
        }
    }

    public synchronized void dismiss(int bid, long eid) {
        synchronized (dbLock) {
            Log.d("IRCCloud", "Dismiss bid" + bid + " eid"+eid);
            Notification n = getNotification(eid);
            if (n != null)
                n.delete();
        }
        if (IRCCloudApplication.getInstance() != null)
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
        updateTeslaUnreadCount();
    }

    public synchronized void addNotification(int cid, int bid, long eid, String from, String message, String chan, String buffer_type, String message_type, String network) {
        long last_eid = getLastSeenEid(bid);
        if (eid <= last_eid) {
            Crashlytics.log("Refusing to add notification for seen eid: " + eid);
            return;
        }

        Notification n = new Notification();
        n.bid = bid;
        n.cid = cid;
        n.eid = eid;
        n.nick = from;
        n.message = TextUtils.htmlEncode(ColorFormatter.strip(message).toString());
        n.chan = chan;
        n.buffer_type = buffer_type;
        n.message_type = message_type;
        n.network = network;

        synchronized (dbLock) {
            n.save();
        }

        if(notificationAddedListener != null)
            notificationAddedListener.onNotificationAdded(n);
    }

    public void deleteOldNotifications() {
        boolean changed = false, pending = false;
        if (mNotificationTimerTask != null) {
            mNotificationTimerTask.cancel();
            pending = true;
        }

        List<Notification> notifications = getNotifications();

        for (Notification n : notifications) {
            long last_seen_eid = getLastSeenEid(n.bid);
            Buffer b = BuffersList.getInstance().getBuffer(n.bid);
            if(b != null)
                last_seen_eid = b.getLast_seen_eid();
            if (last_seen_eid == -1 || n.eid <= last_seen_eid) {
                synchronized (dbLock) {
                    n.delete();
                }
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(n.bid);
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
                changed = true;
                try {
                    if (PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).getBoolean("notify_sony", false))
                        NotificationUtil.deleteEvents(IRCCloudApplication.getInstance().getApplicationContext(), com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.FRIEND_KEY + " = ?", new String[]{String.valueOf(n.bid)});
                } catch (Exception e) {
                }
            }
        }
        if (changed) {
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
            updateTeslaUnreadCount();
        }

        if(pending)
            showNotifications(mTicker);
    }

    public void deleteNotificationsForBid(int bid) {
        Log.d("IRCCloud", "Removing all notifications for bid" + bid);
        List<Notification> notifications = getOtherNotifications();

        if (notifications.size() > 0) {
            for (Notification n : notifications) {
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
            }
        }
        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(bid);

        synchronized (dbLock) {
            notifications = getNotifications();
            for (Notification n : notifications) {
                if (n.bid == bid) {
                    n.delete();
                }
            }
            new Delete().from(Notification_LastSeenEID.class).where(Notification_LastSeenEID_Table.bid.is(bid)).query();
        }
        IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
        try {
            if (PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).getBoolean("notify_sony", false))
                NotificationUtil.deleteEvents(IRCCloudApplication.getInstance().getApplicationContext(), com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.FRIEND_KEY + " = ?", new String[]{String.valueOf(bid)});
        } catch (Exception e) {
            //User has probably uninstalled Sony Liveware
        }
        updateTeslaUnreadCount();
    }

    public long count() {
        synchronized (dbLock) {
            return new Select().from(Notification.class).count();
        }
    }

    public List<Notification> getMessageNotifications() {
        synchronized (dbLock) {
            return new Select().from(Notification.class).where(Notification_Table.bid.isNot(excludeBid))
                    .and(Notification_Table.message_type.isNot("callerid"))
                    .and(Notification_Table.message_type.isNot("callerid_success"))
                    .and(Notification_Table.message_type.isNot("channel_invite"))
                    .orderBy(Notification_Table.bid, true).orderBy(Notification_Table.eid, true).queryList();
        }
    }

    public List<Notification> getOtherNotifications() {
        synchronized (dbLock) {
            return new Select().from(Notification.class).where(
                    OperatorGroup.clause(Notification_Table.bid.isNot(excludeBid))
                            .and(OperatorGroup.clause(Notification_Table.message_type.is("callerid"))
                                    .or(Notification_Table.message_type.is("callerid_success"))
                    .or(Notification_Table.message_type.is("channel_invite"))))
                    .orderBy(Notification_Table.bid, true).orderBy(Notification_Table.eid, true).queryList();
        }
    }

    public Notification getNotification(long eid) {
        synchronized (dbLock) {
            return new Select().from(Notification.class).where(Notification_Table.eid.is(eid)).querySingle();
        }
    }

    public synchronized void excludeBid(int bid) {
        synchronized (dbLock) {
            excludeBid = -1;
            List<Notification> notifications = getOtherNotifications();

            if (notifications.size() > 0) {
                for (Notification n : notifications) {
                    NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
                }
            }
            NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(bid);
            excludeBid = bid;
        }
    }

    private String mTicker = null;

    public void showNotificationsNow() {
        if(mNotificationTimerTask != null)
            mNotificationTimerTask.cancel();
        mNotificationTimerTask = null;
        showMessageNotifications(mTicker);
        showOtherNotifications();
        mTicker = null;
        IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
        updateTeslaUnreadCount();
    }

    public synchronized void showNotifications(String ticker) {
        if (ticker != null)
            mTicker = ColorFormatter.emojify(ticker);

        if (mNotificationTimerTask == null) {
            try {
                TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        showNotificationsNow();
                    }

                    @Override
                    public boolean cancel() {
                        mNotificationTimerTask = null;
                        return super.cancel();
                    }
                };
                mNotificationTimer.schedule(task, 5000);
                mNotificationTimerTask = task;
            } catch (Exception e) {
                NetworkConnection.printStackTraceToCrashlytics(e);
            }
        }
    }

    private void showOtherNotifications() {
        String title = "";
        String text = "";
        String ticker;
        NotificationCompat.Action action = null;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());
        final List<Notification> notifications = getOtherNotifications();

        int notify_type = Integer.parseInt(prefs.getString("notify_type", "1"));
        boolean notify = false;
        if (notify_type == 1 || (notify_type == 2 && NetworkConnection.getInstance().isVisible()))
            notify = true;

        if (notifications.size() > 0 && notify) {
            for (Notification n : notifications) {
                if (!n.shown) {
                    Crashlytics.log(Log.DEBUG, "IRCCloud", "Posting notification for type " + n.message_type);
                    if (n.message_type.equals("callerid")) {
                        title = n.network;
                        text = n.nick + " is trying to contact you";
                        ticker = n.nick + " is trying to contact you on " + n.network;

                        Intent i = new Intent(RemoteInputService.ACTION_REPLY);
                        i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), RemoteInputService.class.getName()));
                        i.putExtra("cid", n.cid);
                        i.putExtra("eid", n.eid);
                        i.putExtra("chan", n.chan);
                        i.putExtra("buffer_type", n.buffer_type);
                        i.putExtra("network", n.network);
                        i.putExtra("to", n.nick);
                        i.putExtra("reply", "/accept " + n.nick);
                        action = new NotificationCompat.Action(R.drawable.ic_wearable_add, "Accept", PendingIntent.getService(IRCCloudApplication.getInstance().getApplicationContext(), (int)(n.eid / 1000), i, PendingIntent.FLAG_UPDATE_CURRENT));
                    } else if(n.message_type.equals("callerid_success")) {
                        title = n.network;
                        text = n.nick + " has been added to your accept list";
                        ticker = n.nick + " has been added to your accept list on " + n.network;
                        Intent i = new Intent(RemoteInputService.ACTION_REPLY);
                        i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), RemoteInputService.class.getName()));
                        i.putExtra("cid", n.cid);
                        i.putExtra("eid", n.eid);
                        i.putExtra("chan", n.chan);
                        i.putExtra("buffer_type", n.buffer_type);
                        i.putExtra("network", n.network);
                        i.putExtra("to", n.nick);
                        action = new NotificationCompat.Action.Builder(R.drawable.ic_wearable_reply, "Message", PendingIntent.getService(IRCCloudApplication.getInstance().getApplicationContext(), (int)(n.eid / 1000), i, PendingIntent.FLAG_UPDATE_CURRENT))
                                .setAllowGeneratedReplies(true)
                                .addRemoteInput(new RemoteInput.Builder("extra_reply").setLabel("Message to " + n.nick).build()).build();
                    } else if(n.message_type.equals("channel_invite")) {
                        title = n.network;
                        text = n.nick + " invited you to join " + n.chan;
                        ticker = text;
                        try {
                            Intent i = new Intent();
                            i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), "com.irccloud.android.MainActivity"));
                            i.setData(Uri.parse(IRCCloudApplication.getInstance().getResources().getString(R.string.IRCCLOUD_SCHEME) + "://cid/" + n.cid + "/" + URLEncoder.encode(n.chan, "UTF-8")));
                            i.putExtra("eid", n.eid);
                            action = new NotificationCompat.Action(R.drawable.ic_wearable_add, "Join", PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), (int)(n.eid / 1000), i, PendingIntent.FLAG_UPDATE_CURRENT));
                        } catch (Exception e) {
                            action = null;
                        }
                    } else {
                        title = n.nick;
                        text = n.message;
                        ticker = n.message;
                        action = null;
                    }
                    if(title != null && text != null)
                        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify((int) (n.eid / 1000), buildNotification(ticker, n.cid, n.bid, new long[]{n.eid}, title, text, 1, null, n.network, null, action, AvatarsList.getInstance().getAvatar(n.cid, n.nick).getBitmap(false, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64, IRCCloudApplication.getInstance().getApplicationContext().getResources().getDisplayMetrics()), false, Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP), AvatarsList.getInstance().getAvatar(n.cid, n.nick).getBitmap(false, 400, false, false)));
                }
            }

            FastStoreModelTransaction
                    .deleteBuilder(FlowManager.getModelAdapter(Notification.class))
                    .addAll(notifications)
                    .build().execute(FlowManager.getWritableDatabase(IRCCloudDatabase.class));
        }
    }

    private boolean hasTouchWiz() {
        try {
            IRCCloudApplication.getInstance().getApplicationContext().getPackageManager().getPackageInfo("com.sec.android.app.launcher", 0);
            return true;
        } catch (Exception e) {
        }
        return false;
    }

    public void createChannel(String id, String title, int importance, String group) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());
        String ringtone = prefs.getString("notify_ringtone", "android.resource://" + IRCCloudApplication.getInstance().getApplicationContext().getPackageName() + "/" + R.raw.digit);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(id, title, importance);
            if(ringtone.length() > 0)
                c.setSound(Uri.parse(ringtone), new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .build());
            c.enableLights(true);
            int led_color = Integer.parseInt(prefs.getString("notify_led_color", "1"));
            if (led_color == 2) {
                c.setLightColor(0xFF0000FF);
            }
            c.enableVibration(prefs.getBoolean("notify_vibrate", true));
            if(group != null)
                c.setGroup(group);
            ((NotificationManager)IRCCloudApplication.getInstance().getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    @SuppressLint("NewApi")
    private android.app.Notification buildNotification(String ticker, int cid, int bid, long[] eids, String title, String text, int count, Intent replyIntent, String network, ArrayList<Notification> messages, NotificationCompat.Action otherAction, Bitmap largeIcon, Bitmap wearBackground) {
        createChannel(String.valueOf(bid), title, NotificationManagerCompat.IMPORTANCE_HIGH, String.valueOf(cid));
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());
        String ringtone = prefs.getString("notify_ringtone", "android.resource://" + IRCCloudApplication.getInstance().getApplicationContext().getPackageName() + "/" + R.raw.digit);
        int defaults = 0;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(IRCCloudApplication.getInstance().getApplicationContext(), String.valueOf(bid))
                .setContentTitle(title + ((network != null && !network.equals(title)) ? (" (" + network + ")") : ""))
                .setContentText(Html.fromHtml(text))
                .setAutoCancel(true)
                .setTicker(ticker)
                .setWhen(eids[0] / 1000)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setLargeIcon(largeIcon)
                .setColor(IRCCloudApplication.getInstance().getApplicationContext().getResources().getColor(R.color.ic_background))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(hasTouchWiz() ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(false);

        if (ticker != null && (System.currentTimeMillis() - prefs.getLong("lastNotificationTime", 0)) > 2000) {
            if (ringtone.length() > 0)
                builder.setSound(Uri.parse(ringtone));
        }

        int led_color = Integer.parseInt(prefs.getString("notify_led_color", "1"));
        if (led_color == 1) {
            defaults = android.app.Notification.DEFAULT_LIGHTS;
        } else if (led_color == 2) {
            builder.setLights(0xFF0000FF, 500, 500);
        }

        if (prefs.getBoolean("notify_vibrate", true) && ticker != null && (System.currentTimeMillis() - prefs.getLong("lastNotificationTime", 0)) > 2000)
            defaults |= android.app.Notification.DEFAULT_VIBRATE;
        else
            builder.setVibrate(new long[]{0L});

        builder.setDefaults(defaults);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong("lastNotificationTime", System.currentTimeMillis());
        editor.commit();

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), "com.irccloud.android.MainActivity"));
        i.putExtra("bid", bid);
        i.setData(Uri.parse("bid://" + bid));
        Intent dismiss = new Intent(IRCCloudApplication.getInstance().getApplicationContext().getResources().getString(R.string.DISMISS_NOTIFICATION));
        dismiss.setData(Uri.parse("irccloud-dismiss://" + bid));
        dismiss.putExtra("bid", bid);
        dismiss.putExtra("eids", eids);

        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(IRCCloudApplication.getInstance().getApplicationContext(), 0, dismiss, PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setContentIntent(PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT));
        builder.setDeleteIntent(dismissPendingIntent);

        WearableExtender wearableExtender = new WearableExtender();
        wearableExtender.setBackground(wearBackground);
        if(messages != null && messages.size() > 0) {
            StringBuilder weartext = new StringBuilder();
            String servernick = getServerNick(messages.get(0).cid);
            NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(servernick);
            style.setConversationTitle(title + ((network != null) ? (" (" + network + ")") : ""));
            for(Notification n : messages) {
                if(n != null && n.message != null && n.message.length() > 0) {
                    if (weartext.length() > 0)
                        weartext.append("<br/>");
                    if (n.message_type.equals("buffer_me_msg")) {
                        style.addMessage(Html.fromHtml(n.message).toString(), n.eid / 1000, "— " + ((n.nick == null) ? servernick : n.nick));
                        weartext.append("<b>— ").append((n.nick == null) ? servernick : n.nick).append("</b> ").append(n.message);
                    } else {
                        style.addMessage(Html.fromHtml(n.message).toString(), n.eid / 1000, n.nick);
                        weartext.append("<b>&lt;").append((n.nick == null) ? servernick : n.nick).append("&gt;</b> ").append(n.message);
                    }
                }
            }

            ArrayList<String> history = new ArrayList<>(messages.size());
            for(int j = messages.size() - 1; j >= 0; j--) {
                Notification n = messages.get(j);
                if(n != null) {
                    if(n.nick == null)
                        history.add(Html.fromHtml(n.message).toString());
                    else
                        break;
                }
            }
            builder.setRemoteInputHistory(history.toArray(new String[history.size()]));
            builder.setStyle(style);

            if(messages.size() > 1) {
                wearableExtender.addPage(new NotificationCompat.Builder(IRCCloudApplication.getInstance().getApplicationContext()).setContentText(Html.fromHtml(weartext.toString())).extend(new WearableExtender().setStartScrollBottom(true)).build());
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                weartext.setLength(0);
                int j = 0;
                for(Notification n : messages) {
                    if(messages.size() - ++j < 3) {
                        if (n != null && n.message != null && n.message.length() > 0) {
                            if (weartext.length() > 0)
                                weartext.append("<br/>");
                            if (n.message_type.equals("buffer_me_msg")) {
                                weartext.append("<b>— ").append((n.nick == null) ? servernick : n.nick).append("</b> ").append(n.message);
                            } else {
                                weartext.append("<b>&lt;").append((n.nick == null) ? servernick : n.nick).append("&gt;</b> ").append(n.message);
                            }
                        }
                    }
                }

                RemoteViews bigContentView = new RemoteViews(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), R.layout.notification_expanded);
                bigContentView.setTextViewText(R.id.title, title + (!title.equals(network) ? (" (" + network + ")") : ""));
                bigContentView.setTextViewText(R.id.text, Html.fromHtml(weartext.toString()));
                bigContentView.setImageViewBitmap(R.id.image, largeIcon);
                bigContentView.setLong(R.id.time, "setTime", eids[0] / 1000);
                if (count > 3) {
                    bigContentView.setViewVisibility(R.id.more, View.VISIBLE);
                    bigContentView.setTextViewText(R.id.more, "+" + (count - 3) + " more");
                } else {
                    bigContentView.setViewVisibility(R.id.more, View.GONE);
                }
                if(replyIntent != null && prefs.getBoolean("notify_quickreply", true)) {
                    bigContentView.setViewVisibility(R.id.actions, View.VISIBLE);
                    bigContentView.setViewVisibility(R.id.action_divider, View.VISIBLE);
                    i = new Intent(IRCCloudApplication.getInstance().getApplicationContext(), QuickReplyActivity.class);
                    i.setData(Uri.parse("irccloud-bid://" + bid));
                    i.putExtras(replyIntent);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PendingIntent quickReplyIntent = PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
                    bigContentView.setOnClickPendingIntent(R.id.action_reply, quickReplyIntent);
                }
                builder.setCustomBigContentView(bigContentView);
            }
        }

        if (replyIntent != null) {
            PendingIntent replyPendingIntent = PendingIntent.getService(IRCCloudApplication.getInstance().getApplicationContext(), bid + 1, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.addAction(new NotificationCompat.Action.Builder(0,
                        "Reply", replyPendingIntent)
                        .setAllowGeneratedReplies(true)
                        .addRemoteInput(new RemoteInput.Builder("extra_reply").setLabel("Reply to " + title).build()).build());
            }

            NotificationCompat.Action.Builder actionBuilder = new NotificationCompat.Action.Builder(R.drawable.ic_wearable_reply,
                    "Reply", replyPendingIntent)
                    .setAllowGeneratedReplies(true)
                    .addRemoteInput(new RemoteInput.Builder("extra_reply").setLabel("Reply to " + title).build());

            NotificationCompat.Action.WearableExtender actionExtender =
                    new NotificationCompat.Action.WearableExtender()
                            .setHintLaunchesActivity(true)
                            .setHintDisplayActionInline(true);

            wearableExtender.addAction(actionBuilder.extend(actionExtender).build());

            NotificationCompat.CarExtender.UnreadConversation.Builder unreadConvBuilder =
                    new NotificationCompat.CarExtender.UnreadConversation.Builder(title + ((network != null) ? (" (" + network + ")") : ""))
                            .setReadPendingIntent(dismissPendingIntent)
                            .setReplyAction(replyPendingIntent, new RemoteInput.Builder("extra_reply").setLabel("Reply to " + title).build());

            if (messages != null) {
                for (Notification n : messages) {
                    if (n != null && n.nick != null && n.message != null && n.message.length() > 0) {
                        if (n.buffer_type.equals("conversation")) {
                            if (n.message_type.equals("buffer_me_msg"))
                                unreadConvBuilder.addMessage("— " + n.nick + " " + Html.fromHtml(n.message).toString());
                            else
                                unreadConvBuilder.addMessage(Html.fromHtml(n.message).toString());
                        } else {
                            if (n.message_type.equals("buffer_me_msg"))
                                unreadConvBuilder.addMessage("— " + n.nick + " " + Html.fromHtml(n.message).toString());
                            else
                                unreadConvBuilder.addMessage(n.nick + " said: " + Html.fromHtml(n.message).toString());
                        }
                    }
                }
            } else {
                unreadConvBuilder.addMessage(text);
            }
            unreadConvBuilder.setLatestTimestamp(eids[count - 1] / 1000);

            builder.extend(new NotificationCompat.CarExtender().setUnreadConversation(unreadConvBuilder.build()));
        }

        if (replyIntent != null && prefs.getBoolean("notify_quickreply", true) && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            i = new Intent(IRCCloudApplication.getInstance().getApplicationContext(), QuickReplyActivity.class);
            i.setData(Uri.parse("irccloud-bid://" + bid));
            i.putExtras(replyIntent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent quickReplyIntent = PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_action_reply, "Quick Reply", quickReplyIntent);
        }

        if(otherAction != null) {
            int drawable = 0;
            if(otherAction.getIcon() == R.drawable.ic_wearable_add)
                drawable = R.drawable.ic_action_add;
            else if(otherAction.getIcon() == R.drawable.ic_wearable_reply)
                drawable = R.drawable.ic_action_reply;
            builder.addAction(new NotificationCompat.Action(drawable, otherAction.getTitle(), otherAction.getActionIntent()));
            wearableExtender.addAction(otherAction);
        }

        builder.extend(wearableExtender);

        return builder.build();
    }

    private void notifyPebble(String title, String body) {
        JSONObject jsonData = new JSONObject();
        try {
            final Intent i = new Intent("com.getpebble.action.SEND_NOTIFICATION");
            jsonData.put("title", title);
            jsonData.put("body", body);
            final String notificationData = new JSONArray().put(jsonData).toString();

            i.putExtra("messageType", "PEBBLE_ALERT");
            i.putExtra("sender", "IRCCloud");
            i.putExtra("notificationData", notificationData);
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(i);
        } catch (Exception e) {
            NetworkConnection.printStackTraceToCrashlytics(e);
        }
    }

    private void showMessageNotifications(String ticker) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());
        String text = "";
        final List<Notification> notifications = getMessageNotifications();

        int notify_type = Integer.parseInt(prefs.getString("notify_type", "1"));
        boolean notify = false;
        if (notify_type == 1 || (notify_type == 2 && NetworkConnection.getInstance().isVisible()))
            notify = true;

        if (notifications.size() > 0 && notify) {
            int lastbid = notifications.get(0).bid;
            int count = 0;
            long[] eids = new long[notifications.size()];
            ArrayList<Notification> messages = new ArrayList<>(notifications.size());
            Notification last = notifications.get(0);
            boolean show = false;
            for (Notification n : notifications) {
                if (n.bid != lastbid) {
                    if (show) {
                        String title = last.chan;
                        if (title == null || title.length() == 0)
                            title = last.nick;
                        if (title == null || title.length() == 0)
                            title = last.network;

                        Intent replyIntent = new Intent(RemoteInputService.ACTION_REPLY);
                        replyIntent.putExtra("bid", last.bid);
                        replyIntent.putExtra("cid", last.cid);
                        replyIntent.putExtra("eids", eids);
                        replyIntent.putExtra("network", last.network);
                        replyIntent.putExtra("chan", last.chan);
                        replyIntent.putExtra("buffer_type", last.buffer_type);
                        replyIntent.putExtra("to", last.chan);

                        String body;
                        if (last.buffer_type.equals("channel")) {
                            if (last.message_type.equals("buffer_me_msg"))
                                body = "<b>— " + ((last.nick != null)?last.nick:getServerNick(last.cid)) + "</b> " + last.message;
                            else
                                body = "<b>&lt;" + ((last.nick != null)?last.nick:getServerNick(last.cid)) + "&gt;</b> " + last.message;
                        } else {
                            if (last.message_type.equals("buffer_me_msg"))
                                body = "— " + ((last.nick != null)?last.nick:getServerNick(last.cid)) + " " + last.message;
                            else
                                body = last.message;
                        }

                        ArrayList<String> lines = new ArrayList<>(Arrays.asList(text.split("<br/>")));
                        while(lines.size() > 3)
                            lines.remove(0);

                        try {
                            Crashlytics.log(Log.DEBUG, "IRCCloud", "Posting notification for type " + last.message_type);
                            NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify(lastbid, buildNotification(ticker, last.cid, lastbid, eids, title, body, count, replyIntent, last.network, messages, null, AvatarsList.getInstance().getAvatar(last.cid, last.nick).getBitmap(false, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64, IRCCloudApplication.getInstance().getApplicationContext().getResources().getDisplayMetrics()), false, Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP), AvatarsList.getInstance().getAvatar(last.cid, last.nick).getBitmap(false, 400, false, false)));
                        } catch (Exception e) {
                            Crashlytics.logException(e);
                        }
                    }
                    lastbid = n.bid;
                    text = "";
                    count = 0;
                    eids = new long[notifications.size()];
                    show = false;
                    messages.clear();
                }

                if (text.length() > 0)
                    text += "<br/>";
                if (n.buffer_type.equals("conversation") && n.message_type.equals("buffer_me_msg"))
                    text += "— " + n.message;
                else if (n.buffer_type.equals("conversation"))
                    text += n.message;
                else if (n.message_type.equals("buffer_me_msg"))
                    text += "<b>— " + ((n.nick != null)?n.nick:getServerNick(n.cid)) + "</b> " + n.message;
                else
                    text += "<b>" + ((n.nick != null)?n.nick:getServerNick(n.cid)) + "</b> " + n.message;

                if (!n.shown) {
                    n.shown = true;
                    show = true;

                    if (n.nick != null && prefs.getBoolean("notify_sony", false)) {
                        long time = System.currentTimeMillis();
                        long sourceId = NotificationUtil.getSourceId(IRCCloudApplication.getInstance().getApplicationContext(), SonyExtensionService.EXTENSION_SPECIFIC_ID);
                        if (sourceId == NotificationUtil.INVALID_ID) {
                            Crashlytics.log(Log.ERROR, "IRCCloud", "Sony LiveWare Manager not configured, disabling Sony notifications");
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean("notify_sony", false);
                            editor.commit();
                        } else {
                            ContentValues eventValues = new ContentValues();
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.EVENT_READ_STATUS, false);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.DISPLAY_NAME, n.nick);

                            if (n.buffer_type.equals("channel") && n.chan != null && n.chan.length() > 0)
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.TITLE, n.chan);
                            else
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.TITLE, n.network);

                            if (n.message_type.equals("buffer_me_msg"))
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.MESSAGE, "— " + Html.fromHtml(n.message).toString());
                            else
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.MESSAGE, Html.fromHtml(n.message).toString());

                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.PERSONAL, 1);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.PUBLISHED_TIME, time);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.SOURCE_ID, sourceId);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.FRIEND_KEY, String.valueOf(n.bid));

                            try {
                                IRCCloudApplication.getInstance().getApplicationContext().getContentResolver().insert(com.sonyericsson.extras.liveware.aef.notification.Notification.Event.URI, eventValues);
                            } catch (IllegalArgumentException e) {
                                Log.e("IRCCloud", "Failed to insert event", e);
                            } catch (SecurityException e) {
                                Log.e("IRCCloud", "Failed to insert event, is Live Ware Manager installed?", e);
                            } catch (SQLException e) {
                                Log.e("IRCCloud", "Failed to insert event", e);
                            }
                        }
                    }

                    if (prefs.getBoolean("notify_pebble", false) && n.nick != null) {
                        String pebbleTitle = n.network + ":\n";
                        String pebbleBody = "";
                        if (n.buffer_type.equals("channel") && n.chan != null && n.chan.length() > 0)
                            pebbleTitle = n.chan + ":\n";

                        if (n.message_type.equals("buffer_me_msg"))
                            pebbleBody = "— " + n.message;
                        else
                            pebbleBody = n.message;

                        if (n.chan != null && n.nick != null && n.nick.length() > 0)
                            notifyPebble(n.nick, pebbleTitle + Html.fromHtml(pebbleBody).toString());
                        else
                            notifyPebble(n.network, pebbleTitle + Html.fromHtml(pebbleBody).toString());
                    }
                }
                messages.add(n);
                eids[count++] = n.eid;
                if(n.nick != null)
                    last = n;
            }

            if (show) {
                String title = last.chan;
                if (title == null || title.length() == 0)
                    title = last.network;

                Intent replyIntent = new Intent(RemoteInputService.ACTION_REPLY);
                replyIntent.putExtra("bid", last.bid);
                replyIntent.putExtra("cid", last.cid);
                replyIntent.putExtra("network", last.network);
                replyIntent.putExtra("eids", eids);
                replyIntent.putExtra("chan", last.chan);
                replyIntent.putExtra("buffer_type", last.buffer_type);
                replyIntent.putExtra("to", last.chan);

                String body = "";
                if (last.buffer_type.equals("channel")) {
                    if (last.message_type.equals("buffer_me_msg"))
                        body = "<b>— " + ((last.nick != null)?last.nick:getServerNick(last.cid)) + "</b> " + last.message;
                    else
                        body = "<b>&lt;" + ((last.nick != null)?last.nick:getServerNick(last.cid)) + "&gt;</b> " + last.message;
                } else {
                    if (last.message_type.equals("buffer_me_msg"))
                        body = "— " + ((last.nick != null)?last.nick:getServerNick(last.cid)) + " " + last.message;
                    else
                        body = last.message;
                }

                ArrayList<String> lines = new ArrayList<>(Arrays.asList(text.split("<br/>")));
                while(lines.size() > 3)
                    lines.remove(0);

                try {
                    Crashlytics.log(Log.DEBUG, "IRCCloud", "Posting notification for type " + last.message_type);
                    NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify(last.bid, buildNotification(ticker, last.cid, last.bid, eids, title, body, count, replyIntent, last.network, messages, null, AvatarsList.getInstance().getAvatar(last.cid, last.nick).getBitmap(false, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64, IRCCloudApplication.getInstance().getApplicationContext().getResources().getDisplayMetrics()), false, Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP), AvatarsList.getInstance().getAvatar(last.cid, last.nick).getBitmap(false, 400, false, false)));
                } catch (Exception e) {
                    Crashlytics.logException(e);
                }
            }

            FlowManager.getDatabase(IRCCloudDatabase.class).getTransactionManager().getSaveQueue().addAll2(notifications);
        }
    }

    public NotificationCompat.Builder alert(int bid, String title, String body) {
        Crashlytics.log(Log.DEBUG, "IRCCloud", "Posting alert notification");
        createChannel("alert", "Alerts", NotificationManagerCompat.IMPORTANCE_DEFAULT, null);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(IRCCloudApplication.getInstance().getApplicationContext(), "alert")
                .setContentTitle(title)
                .setContentText(body)
                .setTicker(body)
                .setAutoCancel(true)
                .setColor(IRCCloudApplication.getInstance().getApplicationContext().getResources().getColor(R.color.ic_background))
                .setSmallIcon(R.drawable.ic_stat_notify);

        Intent i = new Intent();
        i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), "com.irccloud.android.MainActivity"));
        i.putExtra("bid", bid);
        i.setData(Uri.parse("bid://" + bid));
        builder.setContentIntent(PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify(bid, builder.build());

        return builder;
    }

    public void updateTeslaUnreadCount() {
        try {
            IRCCloudApplication.getInstance().getApplicationContext().getPackageManager().getPackageInfo("com.teslacoilsw.notifier", PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }

        try {
            ContentValues cv = new ContentValues();
            cv.put("tag", IRCCloudApplication.getInstance().getApplicationContext().getPackageManager().getLaunchIntentForPackage(IRCCloudApplication.getInstance().getApplicationContext().getPackageName()).getComponent().flattenToString());
            synchronized (dbLock) {
                cv.put("count", new Select().from(Notification.class).where(Notification_Table.nick.isNotNull()).count());
            }
            IRCCloudApplication.getInstance().getApplicationContext().getContentResolver().insert(Uri.parse("content://com.teslacoilsw.notifier/unread_count"), cv);
        } catch (IllegalArgumentException ex) {
        } catch (Exception ex) {
            NetworkConnection.printStackTraceToCrashlytics(ex);
        }
    }

    public void addNotificationGroup(int id, String name) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ((NotificationManager)IRCCloudApplication.getInstance().getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannelGroup(new NotificationChannelGroup(String.valueOf(id), name));
        }
    }

    public void pruneNotificationChannels() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ((NotificationManager) IRCCloudApplication.getInstance().getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE));

            for (NotificationChannelGroup c : nm.getNotificationChannelGroups()) {
                try {
                    if (ServersList.getInstance().getServer(Integer.valueOf(c.getId())) == null)
                        nm.deleteNotificationChannelGroup(c.getId());
                } catch(NumberFormatException e) {
                }
            }

            for (NotificationChannel c : nm.getNotificationChannels()) {
                try {
                    if (BuffersList.getInstance().getBuffer(Integer.valueOf(c.getId())) == null)
                        nm.deleteNotificationChannel(c.getId());
                } catch(NumberFormatException e) {
                }
            }
        }
    }
}