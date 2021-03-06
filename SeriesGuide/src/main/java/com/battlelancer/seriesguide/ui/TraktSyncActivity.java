/*
 * Copyright 2014 Uwe Trottmann
 *
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
 */

package com.battlelancer.seriesguide.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.DialogInterface.OnMultiChoiceClickListener;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import butterknife.ButterKnife;
import butterknife.InjectView;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.MenuItem;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Shows;
import com.battlelancer.seriesguide.settings.TraktSettings;
import com.battlelancer.seriesguide.util.TraktSync;
import com.battlelancer.seriesguide.util.Utils;

/**
 * Displays information and offers tools to upload or download watched flags
 * from trakt.
 */
public class TraktSyncActivity extends BaseActivity {

    private static final int DIALOG_SELECT_SHOWS = 100;

    private static final String TAG = "Trakt Sync";

    private TraktSync mSyncTask;

    @InjectView(R.id.checkBoxSyncUnseen) CheckBox mSyncUnwatchedEpisodes;

    @InjectView(R.id.buttonSyncToTrakt) Button mUploadButton;

    @InjectView(R.id.progressBarSyncToTrakt) ProgressBar mUploadProgressIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trakt_sync);

        setupActionBar();

        setupViews();
    }

    private void setupActionBar() {
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    private void setupViews() {
        ButterKnife.inject(this);

        // Sync to trakt button
        mUploadButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                showDialog(DIALOG_SELECT_SHOWS);
            }
        });
        mUploadProgressIndicator.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isSyncUnseenEpisodes = TraktSettings.isSyncingUnwatchedEpisodes(this);
        mSyncUnwatchedEpisodes.setChecked(isSyncUnseenEpisodes);
    }

    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean(TraktSettings.KEY_SYNC_UNWATCHED_EPISODES,
                mSyncUnwatchedEpisodes.isChecked()).commit();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSyncTask != null && mSyncTask.getStatus() == AsyncTask.Status.RUNNING) {
            mSyncTask.cancel(true);
            mSyncTask = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            super.onBackPressed();
            return true;
        }
        return false;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_SELECT_SHOWS:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                // title and cancel
                builder.setTitle(R.string.trakt_upload);
                builder.setNegativeButton(android.R.string.cancel, null);

                // create checkable show list
                final Cursor shows = getContentResolver().query(Shows.CONTENT_URI, new String[] {
                        Shows._ID, Shows.TITLE, Shows.SYNCENABLED
                }, null, null, Shows.TITLE + " ASC");
                if (shows == null || shows.getCount() == 0) {
                    // no shows, only show cancel button
                    return builder.create();
                }

                String[] showTitles = new String[shows.getCount()];
                boolean[] syncEnabled = new boolean[shows.getCount()];
                for (int i = 0; i < showTitles.length; i++) {
                    shows.moveToNext();
                    showTitles[i] = shows.getString(1);
                    syncEnabled[i] = shows.getInt(2) == 1;
                }

                builder.setMultiChoiceItems(showTitles, syncEnabled,
                        new OnMultiChoiceClickListener() {

                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                    boolean isChecked) {
                                shows.moveToFirst();
                                shows.move(which);
                                final String showId = shows.getString(0);
                                final ContentValues values = new ContentValues();
                                values.put(Shows.SYNCENABLED, isChecked);
                                getContentResolver().update(Shows.buildShowUri(showId), values,
                                        null, null);
                            }
                        });
                builder.setPositiveButton(R.string.trakt_upload,
                        new DialogInterface.OnClickListener() {

                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (mSyncTask != null
                                        && mSyncTask.getStatus() != AsyncTask.Status.FINISHED) {
                                    return;
                                }
                                mSyncTask = (TraktSync) new TraktSync(TraktSyncActivity.this,
                                        mUploadButton, mUploadProgressIndicator,
                                        mSyncUnwatchedEpisodes.isChecked())
                                        .execute();
                                Utils.trackAction(TraktSyncActivity.this, TAG, "Upload to trakt");
                            }
                        });

                return builder.create();
        }
        return null;
    }
}
