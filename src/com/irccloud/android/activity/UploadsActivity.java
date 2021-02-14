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

import android.app.Activity;
import android.app.ActivityManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.crashlytics.android.Crashlytics;
import com.damnhandy.uri.template.UriTemplate;
import com.irccloud.android.AsyncTaskEx;
import com.irccloud.android.ColorFormatter;
import com.irccloud.android.ColorScheme;
import com.irccloud.android.IRCCloudJSONObject;
import com.irccloud.android.NetworkConnection;
import com.irccloud.android.R;
import com.irccloud.android.data.collection.ImageList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class UploadsActivity extends BaseActivity {
    private int page = 0;
    private FilesAdapter adapter = new FilesAdapter();
    private boolean canLoadMore = true;
    private View footer;

    private String to;
    private int cid = -1;
    private String msg;

    private UriTemplate template;

    private static class File implements Serializable {
        private static final long serialVersionUID = 0L;

        String id;
        String name;
        String url;
        String mime_type;
        String extension;
        int size;
        Date date;
        String date_formatted;
        String metadata;
        transient Bitmap image;
        boolean image_failed;
        boolean deleting;
        int position;
    }

    private class FilesAdapter extends BaseAdapter {
        private class ViewHolder {
            TextView date;
            ImageView image;
            TextView extension;
            TextView name;
            TextView metadata;
            ProgressBar progress;
            ImageButton delete;
            ProgressBar delete_progress;
        }

        private ArrayList<File> files = new ArrayList<>();
        private DateFormat dateFormat = DateFormat.getDateTimeInstance();

        public void clear() {
            for(File f : files) {
                f.image = null;
            }
            files.clear();
            notifyDataSetInvalidated();
        }

        public void saveInstanceState(Bundle state) {
            state.putSerializable("adapter", files.toArray(new File[files.size()]));
        }

        public void addFile(String id, String name, String mime_type, String extension, int size, Date date) {
            File f = new File();
            f.id = id;
            f.name = name;
            f.mime_type = mime_type;
            f.extension = extension;
            f.size = size;
            f.date = date;
            if (f.extension != null && f.extension.length() > 1 && f.extension.startsWith("."))
                f.extension = f.extension.substring(1).toUpperCase();

            addFile(f);
        }

        public void addFile(final File f) {
            f.position = files.size();
            files.add(f);

            if(f.image == null && f.mime_type.startsWith("image/")) {
                try {
                    if(ColorFormatter.file_uri_template != null) {
                        f.url = template.set("id", f.id).set("name", f.name).expand();
                        f.image = ImageList.getInstance().getImage(f.id, 320);
                        if(f.image == null)
                            ImageList.getInstance().fetchImage(f.id, 320, new ImageList.OnImageFetchedListener() {

                                @Override
                                public void onImageFetched(Bitmap image) {
                                    f.image = image;
                                    if (f.image == null)
                                        f.image_failed = true;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            notifyDataSetChanged();
                                        }
                                    });
                                }
                            });
                    }
                } catch (OutOfMemoryError e) {
                    f.image_failed = true;
                } catch (Exception e) {
                    f.image_failed = true;
                    NetworkConnection.printStackTraceToCrashlytics(e);
                }
                if (f.image_failed && (f.extension == null || f.extension.length() == 0))
                    f.extension = "IMAGE";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        notifyDataSetChanged();
                    }
                });
            } else {
                if (ColorFormatter.file_uri_template != null) {
                    f.url = template.set("id", f.id).set("name", f.name).expand();
                }
            }
        }

        public void update_positions() {
            int p = 0;
            for(File file : files) {
                file.position = p++;
            }
        }

        public void restoreFile(File f) {
            f.deleting = false;
            files.add(f.position, f);
            update_positions();
            notifyDataSetChanged();
        }

        public void removeFile(File f) {
            files.remove(f);
            update_positions();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int i) {
            return files.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            View row = view;
            ViewHolder holder;

            if (row == null) {
                LayoutInflater inflater = getLayoutInflater();
                row = inflater.inflate(R.layout.row_file, viewGroup, false);

                holder = new ViewHolder();
                holder.date = row.findViewById(R.id.date);
                holder.image = row.findViewById(R.id.image);
                holder.extension = row.findViewById(R.id.extension);
                holder.name = row.findViewById(R.id.name);
                holder.metadata = row.findViewById(R.id.metadata);
                holder.progress = row.findViewById(R.id.progress);
                holder.delete = row.findViewById(R.id.delete);
                holder.delete_progress = row.findViewById(R.id.deleteProgress);

                row.setTag(holder);
            } else {
                holder = (ViewHolder) row.getTag();
            }

            try {
                File f = files.get(i);
                if (f.date_formatted == null)
                    f.date_formatted = dateFormat.format(f.date);
                holder.date.setText(f.date_formatted);
                if (f.extension != null && f.extension.length() > 1)
                    holder.extension.setText(f.extension);
                else
                    holder.extension.setText("???");
                holder.name.setText(f.name);
                if (f.metadata == null) {
                    if (f.size < 1024) {
                        f.metadata = f.size + " B";
                    } else {
                        int exp = (int) (Math.log(f.size) / Math.log(1024));
                        f.metadata = String.format("%.1f ", f.size / Math.pow(1024, exp)) + ("KMGTPE".charAt(exp - 1)) + "B";
                    }
                    f.metadata += " • " + f.mime_type;
                }
                holder.metadata.setText(f.metadata);
                if (!f.image_failed && f.mime_type.startsWith("image/")) {
                    if (f.image != null) {
                        holder.progress.setVisibility(View.GONE);
                        holder.extension.setVisibility(View.GONE);
                        holder.image.setVisibility(View.VISIBLE);
                        holder.image.setImageBitmap(f.image);
                    } else {
                        holder.extension.setVisibility(View.GONE);
                        holder.image.setVisibility(View.GONE);
                        holder.image.setImageBitmap(null);
                        holder.progress.setVisibility(View.VISIBLE);
                    }
                } else {
                    holder.extension.setVisibility(View.VISIBLE);
                    holder.image.setVisibility(View.GONE);
                    holder.progress.setVisibility(View.GONE);
                }
                holder.delete.setOnClickListener(deleteClickListener);
                holder.delete.setColorFilter(ColorScheme.getInstance().colorControlNormal, PorterDuff.Mode.SRC_ATOP);
                holder.delete.setTag(i);
                if(f.deleting) {
                    holder.delete.setVisibility(View.GONE);
                    holder.delete_progress.setVisibility(View.VISIBLE);
                } else {
                    holder.delete.setVisibility(View.VISIBLE);
                    holder.delete_progress.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                // TODO Auto-generated catch block
                NetworkConnection.printStackTraceToCrashlytics(e);
            }

            return row;
        }
    }

    private class FetchFilesTask extends AsyncTaskEx<Void, Void, JSONObject> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            canLoadMore = false;
        }

        @Override
        protected JSONObject doInBackground(Void... params) {
            try {
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                return NetworkConnection.getInstance().files(++page);
            } catch (IOException e) {
                NetworkConnection.printStackTraceToCrashlytics(e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(JSONObject jsonObject) {
            if (jsonObject != null) {
                try {
                    if (jsonObject.getBoolean("success")) {
                        JSONArray files = jsonObject.getJSONArray("files");
                        Log.e("IRCCloud", "Got " + files.length() + " files for page " + page);
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject file = files.getJSONObject(i);
                            adapter.addFile(file.getString("id"), file.getString("name"), file.getString("mime_type"), file.getString("extension"), file.getInt("size"), new Date(file.getLong("date") * 1000L));
                        }
                        adapter.notifyDataSetChanged();
                        canLoadMore = files.length() > 0 && adapter.getCount() < jsonObject.getInt("total");
                        if(!canLoadMore)
                            footer.findViewById(R.id.progress).setVisibility(View.GONE);
                    } else {
                        page--;
                        Log.e("IRCCloud", "Failed: " + jsonObject.toString());
                        if(jsonObject.has("message") && jsonObject.getString("message").equals("server_error")) {
                            canLoadMore = true;
                            new FetchFilesTask().execute((Void)null);
                        } else {
                            canLoadMore = false;
                        }
                    }
                } catch (JSONException e) {
                    page--;
                    NetworkConnection.printStackTraceToCrashlytics(e);
                }
            } else {
                page--;
                canLoadMore = true;
                new FetchFilesTask().execute((Void) null);
            }
            checkEmpty();
        }
    }

    private View.OnClickListener deleteClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            final File f = (File)adapter.getItem((Integer) view.getTag());
            if(f != null) {
                AlertDialog.Builder builder = new AlertDialog.Builder(UploadsActivity.this);
                builder.setTitle("Delete File");
                if (f.name != null && f.name.length() > 0) {
                    builder.setMessage("Are you sure you want to delete '" + f.name + "'?");
                } else {
                    builder.setMessage("Are you sure you want to delete this file?");
                }
                builder.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        NetworkConnection.getInstance().deleteFile(f.id, new NetworkConnection.IRCResultCallback() {
                            @Override
                            public void onIRCResult(IRCCloudJSONObject result) {
                                if(result.getBoolean("success")) {
                                    Log.d("IRCCloud", "File deleted successfully");
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            adapter.removeFile(f);
                                            View.OnClickListener undo = new View.OnClickListener() {
                                                @Override
                                                public void onClick(View view) {
                                                    NetworkConnection.getInstance().restoreFile(f.id, null);
                                                    adapter.restoreFile(f);
                                                }
                                            };
                                            if(f.name != null && f.name.length() > 0)
                                                Snackbar.make(findViewById(android.R.id.list), f.name + " was deleted.", Snackbar.LENGTH_LONG).setAction("UNDO", undo).show();
                                            else
                                                Snackbar.make(findViewById(android.R.id.list), "File was deleted.", Snackbar.LENGTH_LONG).setAction("UNDO", undo).show();
                                        }
                                    });
                                } else {
                                    Crashlytics.log(Log.ERROR, "IRCCloud", "Delete failed: " + result.toString());
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            AlertDialog.Builder builder = new AlertDialog.Builder(UploadsActivity.this);
                                            builder.setTitle("Error");
                                            if(f.name != null && f.name.length() > 0)
                                                builder.setMessage("Unable to delete '" + f.name + "'.  Please try again shortly.");
                                            else
                                                builder.setMessage("Unable to delete file.  Please try again shortly.");
                                            builder.setPositiveButton("Close", null);
                                            builder.show();
                                            f.deleting = false;
                                            adapter.notifyDataSetChanged();
                                        }
                                    });
                                }
                            }
                        });
                        f.deleting = true;
                        adapter.notifyDataSetChanged();
                        checkEmpty();
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                });
                AlertDialog d = builder.create();
                d.setOwnerActivity(UploadsActivity.this);
                d.show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if(ColorFormatter.file_uri_template != null)
            template = UriTemplate.fromTemplate(ColorFormatter.file_uri_template);
        super.onCreate(savedInstanceState);
        setTheme(ColorScheme.getDialogWhenLargeTheme(ColorScheme.getUserTheme()));
        onMultiWindowModeChanged(isMultiWindow());

        try {
            java.io.File httpCacheDir = new java.io.File(getCacheDir(), "http");
            long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize);
        } catch (IOException e) {
            Log.i("IRCCloud", "HTTP response cache installation failed:" + e);
        }
        setContentView(R.layout.listview);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setVisibility(View.VISIBLE);
        setSupportActionBar(toolbar);

        if(getSupportActionBar() != null) {
            getSupportActionBar().setElevation(0);
        }

        toolbar.setNavigationIcon(android.support.v7.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        if(savedInstanceState != null && savedInstanceState.containsKey("cid")) {
            cid = savedInstanceState.getInt("cid");
            to = savedInstanceState.getString("to");
            msg = savedInstanceState.getString("msg");
            page = savedInstanceState.getInt("page");
            try {
                File[] files = (File[]) savedInstanceState.getSerializable("adapter");
                for (File f : files) {
                    adapter.addFile(f);
                }
                adapter.notifyDataSetChanged();
            } catch (ClassCastException e) {
                adapter.clear();
                adapter.notifyDataSetInvalidated();
                page = 0;
            }
        }

        footer = getLayoutInflater().inflate(R.layout.messageview_header, null);
        ListView listView = findViewById(android.R.id.list);
        listView.setAdapter(adapter);
        listView.addFooterView(footer);
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView absListView, int i) {

            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (canLoadMore && firstVisibleItem + visibleItemCount > totalItemCount - 4) {
                    canLoadMore = false;
                    new FetchFilesTask().execute((Void) null);
                }
            }
        });
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {

            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                if (i < adapter.getCount()) {
                    final File f = (File) adapter.getItem(i);

                    AlertDialog.Builder builder = new AlertDialog.Builder(UploadsActivity.this);
                    final View v = getLayoutInflater().inflate(R.layout.dialog_upload, null);
                    final EditText messageinput = v.findViewById(R.id.message);
                    messageinput.setText(msg);
                    final ImageView thumbnail = v.findViewById(R.id.thumbnail);

                    v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (messageinput.hasFocus()) {
                                v.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        v.scrollTo(0, v.getBottom());
                                    }
                                });
                            }
                        }
                    });

                    if (f.mime_type.startsWith("image/")) {
                        try {
                            thumbnail.setImageBitmap(f.image);
                            thumbnail.setVisibility(View.VISIBLE);
                            thumbnail.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    Intent i = new Intent(UploadsActivity.this, ImageViewerActivity.class);
                                    i.setData(Uri.parse(f.url));
                                    startActivity(i);
                                }
                            });
                            thumbnail.setClickable(true);
                        } catch (Exception e) {
                            NetworkConnection.printStackTraceToCrashlytics(e);
                        }
                    } else {
                        thumbnail.setVisibility(View.GONE);
                    }

                    ((TextView) v.findViewById(R.id.filesize)).setText(f.metadata);
                    v.findViewById(R.id.filename).setVisibility(View.GONE);
                    v.findViewById(R.id.filename_heading).setVisibility(View.GONE);

                    builder.setTitle("Send A File To " + to);
                    builder.setView(v);
                    builder.setPositiveButton("Send", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String message = messageinput.getText().toString();
                            if (message.length() > 0)
                                message += " ";
                            message += f.url;

                            dialog.dismiss();
                            if (getParent() == null) {
                                setResult(Activity.RESULT_OK);
                            } else {
                                getParent().setResult(Activity.RESULT_OK);
                            }
                            finish();

                            NetworkConnection.getInstance().say(cid, to, message, null);
                        }
                    });
                    builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });
                    AlertDialog d = builder.create();
                    d.setOwnerActivity(UploadsActivity.this);
                    d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    d.show();
                }
            }
        });
        NetworkConnection.getInstance().addHandler(this);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if(getWindowManager().getDefaultDisplay().getWidth() > TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 800, getResources().getDisplayMetrics()) && !isMultiWindow()) {
            params.width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 800, getResources().getDisplayMetrics());
            params.height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 800, getResources().getDisplayMetrics());
        } else {
            params.width = -1;
            params.height = -1;
        }
        getWindow().setAttributes(params);
    }

    @Override
    public void onResume() {
        if(ColorFormatter.file_uri_template != null)
            template = UriTemplate.fromTemplate(ColorFormatter.file_uri_template);
        super.onResume();

        if(cid == -1) {
            cid = getIntent().getIntExtra("cid", -1);
            to = getIntent().getStringExtra("to");
            msg = getIntent().getStringExtra("msg");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(adapter != null)
            adapter.clear();

        HttpResponseCache cache = HttpResponseCache.getInstalled();
        if (cache != null) {
            cache.flush();
        }
        NetworkConnection.getInstance().removeHandler(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt("cid", cid);
        outState.putString("to", to);
        outState.putString("msg", msg);
        outState.putInt("page", page);
        adapter.saveInstanceState(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkEmpty() {
        if(adapter.getCount() == 0 && !canLoadMore) {
            findViewById(android.R.id.list).setVisibility(View.GONE);
            TextView empty = findViewById(android.R.id.empty);
            empty.setVisibility(View.VISIBLE);
            empty.setText("You haven't uploaded any files to IRCCloud yet.");
        } else {
            findViewById(android.R.id.list).setVisibility(View.VISIBLE);
            findViewById(android.R.id.empty).setVisibility(View.GONE);
        }
    }
}