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

package com.irccloud.android.activity;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.support.graphics.drawable.VectorDrawableCompat;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.ActionBar;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

import com.irccloud.android.ColorScheme;
import com.irccloud.android.IRCCloudApplication;
import com.irccloud.android.IRCCloudJSONObject;
import com.irccloud.android.NetworkConnection;
import com.irccloud.android.R;
import com.irccloud.android.data.model.Buffer;
import com.irccloud.android.data.collection.ServersList;
import com.irccloud.android.fragment.EditConnectionFragment;

public class EditConnectionActivity extends BaseActivity implements NetworkConnection.IRCEventHandler {
    int cidToOpen = -1;
    int cid = -1;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_connection);

        getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
        getSupportActionBar().setCustomView(R.layout.actionbar_edit_connection);
        getSupportActionBar().setElevation(0);

        TextView t = findViewById(R.id.action_cancel);
        Drawable d;
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            d = VectorDrawableCompat.create(getResources(), R.drawable.ic_action_cancel, null).mutate();
        else
            d = getResources().getDrawable(R.drawable.ic_action_cancel, null).mutate();
        d.setColorFilter(ColorScheme.getInstance().navBarHeadingColor, PorterDuff.Mode.SRC_ATOP);
        t.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);

        t = findViewById(R.id.action_done);
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            d = VectorDrawableCompat.create(getResources(), R.drawable.ic_action_save, null).mutate();
        else
            d = getResources().getDrawable(R.drawable.ic_action_save, null).mutate();
        d.setColorFilter(ColorScheme.getInstance().navBarHeadingColor, PorterDuff.Mode.SRC_ATOP);
        t.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        final EditConnectionFragment newFragment = new EditConnectionFragment();
        if (getIntent() != null && getIntent().hasExtra("cid")) {
            newFragment.setCid(getIntent().getIntExtra("cid", -1));
            cid = getIntent().getIntExtra("cid", -1);
        }
        if (getIntent() != null && getIntent().hasExtra("hostname"))
            newFragment.default_hostname = getIntent().getStringExtra("hostname");
        if (getIntent() != null && getIntent().hasExtra("channels"))
            newFragment.default_channels = getIntent().getStringExtra("channels");
        newFragment.default_port = getIntent().getIntExtra("port", 6667);
        ft.replace(R.id.EditConnectionFragment, newFragment);
        ft.commit();

        getSupportActionBar().getCustomView().findViewById(R.id.action_cancel).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                if (ServersList.getInstance().count() < 1) {
                    NetworkConnection.getInstance().logout();
                    Intent i = new Intent(EditConnectionActivity.this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
                finish();
            }

        });

        getSupportActionBar().getCustomView().findViewById(R.id.action_done).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                newFragment.save(new NetworkConnection.IRCResultCallback() {
                    @Override
                    public void onIRCResult(IRCCloudJSONObject result) {
                        if(result.getBoolean("success")) {
                            if (cid != -1)
                                finish();
                            else
                                cidToOpen = result.cid();
                        } else {
                            final String message = result.getString("message");
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    switch (message) {
                                        case "passworded_servers":
                                            Toast.makeText(EditConnectionActivity.this, "You can’t connect to passworded servers with free accounts.", Toast.LENGTH_LONG).show();
                                            break;
                                        case "networks":
                                            Toast.makeText(EditConnectionActivity.this, "You've exceeded the connection limit for free accounts.", Toast.LENGTH_LONG).show();
                                            break;
                                        case "unverified":
                                            Toast.makeText(EditConnectionActivity.this, "You can’t connect to external servers until you confirm your email address.", Toast.LENGTH_LONG).show();
                                            break;
                                        case "sts_policy":
                                            Toast.makeText(IRCCloudApplication.getInstance().getApplicationContext(), "You can’t disable secure connections to this network because it’s using a strict transport security policy.", Toast.LENGTH_LONG).show();
                                            break;
                                        default:
                                            Toast.makeText(EditConnectionActivity.this, "Unable to add connection: invalid " + message, Toast.LENGTH_LONG).show();
                                            break;
                                    }
                                }
                            });
                        }
                    }
                });
            }

        });

        NetworkConnection.getInstance().addHandler(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkConnection.getInstance().removeHandler(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        IRCCloudApplication.getInstance().onPause(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        IRCCloudApplication.getInstance().onResume(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }

    public void onIRCEvent(int what, Object o) {
        IRCCloudJSONObject obj;
        Buffer buffer;
        switch (what) {
            case NetworkConnection.EVENT_MAKEBUFFER:
                buffer = (Buffer) o;
                if (buffer.getCid() == cidToOpen) {
                    Intent i = new Intent(EditConnectionActivity.this, MainActivity.class);
                    i.putExtra("bid", buffer.getBid());
                    startActivity(i);
                    finish();
                }
                break;
            default:
                break;
        }
    }
}
