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

import android.accounts.Account;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SyncStatusObserver;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewPager;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.astuetz.PagerSlidingTabStrip;
import com.battlelancer.seriesguide.BuildConfig;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SeriesGuideApplication;
import com.battlelancer.seriesguide.adapters.TabStripAdapter;
import com.battlelancer.seriesguide.api.Intents;
import com.battlelancer.seriesguide.billing.BillingActivity;
import com.battlelancer.seriesguide.billing.IabHelper;
import com.battlelancer.seriesguide.billing.IabResult;
import com.battlelancer.seriesguide.billing.Inventory;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Shows;
import com.battlelancer.seriesguide.service.NotificationService;
import com.battlelancer.seriesguide.settings.ActivitySettings;
import com.battlelancer.seriesguide.settings.AppSettings;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.settings.TraktCredentials;
import com.battlelancer.seriesguide.sync.AccountUtils;
import com.battlelancer.seriesguide.sync.SgSyncAdapter;
import com.battlelancer.seriesguide.ui.FirstRunFragment.OnFirstRunDismissedListener;
import com.battlelancer.seriesguide.ui.dialogs.AddDialogFragment;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.EpisodeTools;
import com.battlelancer.seriesguide.util.ImageProvider;
import com.battlelancer.seriesguide.util.LatestEpisodeUpdateService;
import com.battlelancer.seriesguide.util.RemoveShowWorkerFragment;
import com.battlelancer.seriesguide.util.TaskManager;
import com.battlelancer.seriesguide.util.Utils;
import com.battlelancer.thetvdbapi.TheTVDB;
import de.greenrobot.event.EventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import timber.log.Timber;

/**
 * Provides the apps main screen, displaying a list of shows and their next episodes.
 */
public class ShowsActivity extends BaseTopShowsActivity implements
        AddDialogFragment.OnAddShowListener, OnFirstRunDismissedListener {

    protected static final String TAG = "Shows";

    public static final int ADD_SHOW_LOADER_ID = 1;

    private static final int UPDATE_SUCCESS = 100;

    private static final int UPDATE_INCOMPLETE = 104;

    // Background Task States
    private static final String STATE_ART_IN_PROGRESS = "seriesguide.art.inprogress";

    private static final String STATE_ART_PATHS = "seriesguide.art.paths";

    private static final String STATE_ART_INDEX = "seriesguide.art.index";

    private IabHelper mHelper;

    private Bundle mSavedState;

    private FetchPosterTask mArtTask;

    private ProgressBar mProgressBar;

    private Object mSyncObserverHandle;

    private ShowsTabPageAdapter mTabsAdapter;

    private ViewPager mViewPager;

    private ProgressDialog mProgressDialog;

    public interface InitBundle {

        String SELECTED_TAB = "selectedtab";

        int INDEX_TAB_SHOWS = 0;
        int INDEX_TAB_UPCOMING = 1;
        int INDEX_TAB_RECENT = 2;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.shows);
        setupNavDrawer();

        // Set up a sync account if needed
        if (!AccountUtils.isAccountExists(this)) {
            AccountUtils.createAccount(this);
        }

        onUpgrade();

        // may launch from a notification, then set last cleared time
        NotificationService.handleDeleteIntent(this, getIntent());

        // handle implicit intents from other apps
        handleViewIntents();

        setUpActionBar();
        setupViews();
        setInitialTab(savedInstanceState, getIntent().getExtras());

        // query in-app purchases (only if not already qualified)
        if (Utils.requiresPurchaseCheck(this)) {
            mHelper = new IabHelper(this, BillingActivity.getPublicKey(this));
            mHelper.enableDebugLogging(BuildConfig.DEBUG);

            Timber.i("Starting In-App Billing helper setup.");
            mHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
                public void onIabSetupFinished(IabResult result) {
                    Timber.d("Setup finished.");

                    if (!result.isSuccess()) {
                        // Oh noes, there was a problem. But do not go crazy.
                        disposeIabHelper();
                        return;
                    }

                    // Have we been disposed of in the meantime? If so, quit.
                    if (mHelper == null) {
                        return;
                    }

                    // Hooray, IAB is fully set up. Now, let's get an inventory
                    // of stuff we own.
                    Timber.d("Setup successful. Querying inventory.");
                    mHelper.queryInventoryAsync(mGotInventoryListener);
                }
            });
        }
    }

    /**
     * Handles further behavior, if this activity was launched through one of the {@link
     * Intents} action filters defined in the manifest.
     */
    private void handleViewIntents() {
        String action = getIntent().getAction();
        if (TextUtils.isEmpty(action)) {
            return;
        }

        Intent intent = null;

        // view an episode
        if (Intents.ACTION_VIEW_EPISODE.equals(action)) {
            int episodeTvdbId = getIntent().getIntExtra(Intents.EXTRA_EPISODE_TVDBID, 0);
            if (episodeTvdbId > 0 && EpisodeTools.isEpisodeExists(this, episodeTvdbId)) {
                // episode exists, display it
                intent = new Intent(this, EpisodesActivity.class)
                        .putExtra(EpisodesActivity.InitBundle.EPISODE_TVDBID, episodeTvdbId);
            } else {
                // no such episode, offer to add show
                int showTvdbId = getIntent().getIntExtra(Intents.EXTRA_SHOW_TVDBID, 0);
                if (showTvdbId > 0) {
                    SearchResult show = new SearchResult();
                    show.tvdbid = showTvdbId;
                    AddDialogFragment.showAddDialog(show, getSupportFragmentManager());
                }
            }
        }
        // view a show
        else if (Intents.ACTION_VIEW_SHOW.equals(action)) {
            int showTvdbId = getIntent().getIntExtra(Intents.EXTRA_SHOW_TVDBID, 0);
            if (showTvdbId <= 0) {
                return;
            }
            if (DBUtils.isShowExists(this, showTvdbId)) {
                // show exists, display it
                intent = new Intent(this, OverviewActivity.class)
                        .putExtra(OverviewFragment.InitBundle.SHOW_TVDBID, showTvdbId);
            } else {
                // no such show, offer to add it
                SearchResult show = new SearchResult();
                show.tvdbid = showTvdbId;
                AddDialogFragment.showAddDialog(show, getSupportFragmentManager());
            }
        }

        if (intent != null) {
            startActivity(intent);
            overridePendingTransition(R.anim.blow_up_enter, R.anim.blow_up_exit);
        }
    }

    private void setUpActionBar() {
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowTitleEnabled(false);
    }

    private void setupViews() {
        mViewPager = (ViewPager) findViewById(R.id.pagerShows);
        PagerSlidingTabStrip tabs = (PagerSlidingTabStrip) findViewById(R.id.tabsShows);

        mTabsAdapter = new ShowsTabPageAdapter(getSupportFragmentManager(),
                this, mViewPager, tabs);

        // shows tab (or first run fragment)
        if (!FirstRunFragment.hasSeenFirstRunFragment(this)) {
            mTabsAdapter.addTab(R.string.shows, FirstRunFragment.class, null);
        } else {
            mTabsAdapter.addTab(R.string.shows, ShowsFragment.class, null);
        }

        // upcoming tab
        final Bundle argsUpcoming = new Bundle();
        argsUpcoming.putString(ActivityFragment.InitBundle.TYPE,
                ActivityFragment.ActivityType.UPCOMING);
        argsUpcoming.putString(ActivityFragment.InitBundle.ANALYTICS_TAG, "Upcoming");
        argsUpcoming.putInt(ActivityFragment.InitBundle.LOADER_ID, 10);
        argsUpcoming.putInt(ActivityFragment.InitBundle.EMPTY_STRING_ID, R.string.noupcoming);
        mTabsAdapter.addTab(R.string.upcoming, ActivityFragment.class, argsUpcoming);

        // recent tab
        final Bundle argsRecent = new Bundle();
        argsRecent
                .putString(ActivityFragment.InitBundle.TYPE, ActivityFragment.ActivityType.RECENT);
        argsRecent.putString(ActivityFragment.InitBundle.ANALYTICS_TAG, "Recent");
        argsRecent.putInt(ActivityFragment.InitBundle.LOADER_ID, 20);
        argsRecent.putInt(ActivityFragment.InitBundle.EMPTY_STRING_ID, R.string.norecent);
        mTabsAdapter.addTab(R.string.recent, ActivityFragment.class, argsRecent);

        // trakt friends tab
        if (TraktCredentials.get(this).hasCredentials()) {
            mTabsAdapter.addTab(R.string.friends, TraktFriendsFragment.class, null);
        }

        // display new tabs
        mTabsAdapter.notifyTabsChanged();

        // progress bar
        mProgressBar = (ProgressBar) findViewById(R.id.progressBarShows);
    }

    /**
     * Tries to restore the current tab from the given state, if that fails from the given
     * intent extras. If that fails as well, uses the last known selected tab.
     */
    private void setInitialTab(Bundle savedInstanceState, Bundle intentExtras) {
        int selection;
        if (savedInstanceState != null) {
            selection = savedInstanceState.getInt("index");
        } else if (intentExtras != null) {
            selection = intentExtras.getInt(InitBundle.SELECTED_TAB,
                    ActivitySettings.getDefaultActivityTabPosition(this));
        } else {
            // use last saved selection
            selection = ActivitySettings.getDefaultActivityTabPosition(this);
        }

        // never select a non-existent tab
        if (selection > mTabsAdapter.getCount() - 1) {
            selection = 0;
        }

        mViewPager.setCurrentItem(selection);
    }

    @Override
    protected void onStart() {
        super.onStart();

        setDrawerSelectedItem(BaseNavDrawerActivity.MENU_ITEM_SHOWS_POSITION);

        // check for running show removal worker
        Fragment f = getSupportFragmentManager().findFragmentByTag(RemoveShowWorkerFragment.TAG);
        if (f != null && !((RemoveShowWorkerFragment) f).isTaskFinished()) {
            showProgressDialog();
        }
        // now listen to events
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        setInitialTab(null, intent.getExtras());
    }

    @Override
    protected void onResume() {
        super.onResume();

        startService(new Intent(this, LatestEpisodeUpdateService.class));

        if (mSavedState != null) {
            restoreLocalState(mSavedState);
        }

        mSyncStatusObserver.onStatusChanged(0);

        // Watch for sync state changes
        final int mask = ContentResolver.SYNC_OBSERVER_TYPE_PENDING |
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE;
        mSyncObserverHandle = ContentResolver.addStatusChangeListener(mask, mSyncStatusObserver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSyncObserverHandle != null) {
            ContentResolver.removeStatusChangeListener(mSyncObserverHandle);
            mSyncObserverHandle = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // stop listening to events
        EventBus.getDefault().unregister(this);
        // now prevent dialog from restoring itself (we would loose ref to it)
        hideProgressDialog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onCancelTasks();
        disposeIabHelper();
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        restoreLocalState(savedInstanceState);
        mSavedState = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveArtTask(outState);
        outState.putInt("index", getSupportActionBar().getSelectedNavigationIndex());
        mSavedState = outState;
    }

    private void restoreLocalState(Bundle savedInstanceState) {
        restoreArtTask(savedInstanceState);
    }

    private void restoreArtTask(Bundle savedInstanceState) {
        if (savedInstanceState.getBoolean(STATE_ART_IN_PROGRESS)) {
            ArrayList<String> paths = savedInstanceState.getStringArrayList(STATE_ART_PATHS);
            int index = savedInstanceState.getInt(STATE_ART_INDEX);

            if (paths != null) {
                mArtTask = (FetchPosterTask) new FetchPosterTask(paths, index).execute();
            }
        }
    }

    private void saveArtTask(Bundle outState) {
        final FetchPosterTask task = mArtTask;
        if (task != null && task.getStatus() != AsyncTask.Status.FINISHED) {
            task.cancel(true);

            outState.putBoolean(STATE_ART_IN_PROGRESS, true);
            outState.putStringArrayList(STATE_ART_PATHS, task.mPaths);
            outState.putInt(STATE_ART_INDEX, task.mFetchCount.get());

            mArtTask = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.seriesguide_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // If the nav drawer is open, hide action items related to the content
        // view
        boolean isDrawerOpen = isDrawerOpen();
        menu.findItem(R.id.menu_add_show).setVisible(!isDrawerOpen);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_add_show) {
            fireTrackerEvent("Add show");
            startActivity(new Intent(this, AddActivity.class));
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            return true;
        } else if (itemId == R.id.menu_search) {
            startActivity(new Intent(this, SearchActivity.class));
            fireTrackerEvent("Search");
            return true;
        } else if (itemId == R.id.menu_update) {
            SgSyncAdapter.requestSyncImmediate(this, SgSyncAdapter.SyncType.DELTA, 0, true);
            fireTrackerEvent("Update (outdated)");

            return true;
        } else if (itemId == R.id.menu_fullupdate) {
            SgSyncAdapter.requestSyncImmediate(this, SgSyncAdapter.SyncType.FULL, 0, true);
            fireTrackerEvent("Update (all)");

            return true;
        } else if (itemId == R.id.menu_updateart) {
            fireTrackerEvent("Fetch posters");
            if (isArtTaskRunning()) {
                return true;
            }
            if (Utils.isAllowedLargeDataConnection(this, true)) {
                Toast.makeText(this, getString(R.string.arttask_start), Toast.LENGTH_LONG).show();
                mArtTask = (FetchPosterTask) new FetchPosterTask().execute();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // prevent navigating to top activity as this is the top activity
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    private class FetchPosterTask extends AsyncTask<Void, Void, Integer> {

        final AtomicInteger mFetchCount = new AtomicInteger();

        ArrayList<String> mPaths;

        protected FetchPosterTask() {
        }

        protected FetchPosterTask(ArrayList<String> paths, int index) {
            mPaths = paths;
            mFetchCount.set(index);
        }

        @Override
        protected void onPreExecute() {
            setProgressVisibility(true);
        }

        @Override
        protected Integer doInBackground(Void... params) {
            // fetch all available poster paths
            if (mPaths == null) {
                Cursor shows = getContentResolver().query(Shows.CONTENT_URI, new String[] {
                        Shows.POSTER
                }, null, null, null);
                if (shows == null) {
                    return UPDATE_INCOMPLETE;
                }
                if (shows.getCount() == 0) {
                    // there are no shows
                    shows.close();
                    return UPDATE_SUCCESS;
                }

                // build a list of poster paths
                mPaths = new ArrayList<>();
                while (shows.moveToNext()) {
                    String imagePath = shows.getString(0);
                    if (!TextUtils.isEmpty(imagePath)) {
                        mPaths.add(imagePath);
                    }
                }

                shows.close();
            }

            int resultCode = UPDATE_SUCCESS;
            final List<String> list = mPaths;
            final int count = list.size();
            final AtomicInteger fetchCount = mFetchCount;

            // try to fetch image for each path
            for (int i = fetchCount.get(); i < count; i++) {
                if (isCancelled() ||
                        !Utils.isAllowedLargeDataConnection(ShowsActivity.this, false)) {
                    // cancelled or connection not available any longer
                    return UPDATE_INCOMPLETE;
                }

                if (!TheTVDB.fetchArt(list.get(i), true, ShowsActivity.this)) {
                    resultCode = UPDATE_INCOMPLETE;
                }

                fetchCount.incrementAndGet();
            }

            getContentResolver().notifyChange(Shows.CONTENT_URI, null);

            return resultCode;
        }

        @Override
        protected void onPostExecute(Integer resultCode) {
            switch (resultCode) {
                case UPDATE_SUCCESS:
                    Toast.makeText(getApplicationContext(), getString(R.string.done),
                            Toast.LENGTH_SHORT).show();

                    Utils.trackCustomEvent(getApplicationContext(), TAG, "Poster Task", "Success");
                    break;
                case UPDATE_INCOMPLETE:
                    Toast.makeText(getApplicationContext(), getString(R.string.arttask_incomplete),
                            Toast.LENGTH_LONG).show();

                    Utils.trackCustomEvent(getApplicationContext(), TAG, "Poster Task",
                            "Incomplete");
                    break;
            }

            setProgressVisibility(false);
        }

        @Override
        protected void onCancelled() {
            setProgressVisibility(false);
        }
    }

    private boolean isArtTaskRunning() {
        if (mArtTask != null && mArtTask.getStatus() == AsyncTask.Status.RUNNING) {
            Toast.makeText(this, getString(R.string.update_inprogress), Toast.LENGTH_LONG).show();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Called if the user adds a show from {@link com.battlelancer.seriesguide.ui.TraktFriendsFragment}.
     */
    @Override
    public void onAddShow(SearchResult show) {
        TaskManager.getInstance(this).performAddTask(show);
    }

    public void onCancelTasks() {
        if (mArtTask != null && mArtTask.getStatus() == AsyncTask.Status.RUNNING) {
            mArtTask.cancel(true);
            mArtTask = null;
        }
    }

    /**
     * Called from {@link com.battlelancer.seriesguide.util.RemoveShowWorkerFragment}.
     */
    public void onEventMainThread(RemoveShowWorkerFragment.OnRemovingShowEvent event) {
        showProgressDialog();
    }

    /**
     * Called from {@link com.battlelancer.seriesguide.util.RemoveShowWorkerFragment}.
     */
    public void onEventMainThread(RemoveShowWorkerFragment.OnShowRemovedEvent event) {
        hideProgressDialog();
    }

    private void showProgressDialog() {
        if (mProgressDialog == null) {
            mProgressDialog = new ProgressDialog(this);
            mProgressDialog.setCancelable(false);
        }
        mProgressDialog.show();
    }

    private void hideProgressDialog() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
        mProgressDialog = null;
    }

    /**
     * Runs any upgrades necessary if coming from earlier versions.
     */
    private void onUpgrade() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final int lastVersion = AppSettings.getLastVersionCode(this);
        final int currentVersion = BuildConfig.VERSION_CODE;

        if (lastVersion < currentVersion) {
            Editor editor = prefs.edit();

            int VER_TRAKT_SEC_CHANGES;
            int VER_SUMMERTIME_FIX;
            int VER_HIGHRES_THUMBS;
            if (SeriesGuideApplication.FLAVOR_INTERNAL.equals(BuildConfig.FLAVOR)) {
                // internal dev version
                VER_TRAKT_SEC_CHANGES = 131;
                VER_SUMMERTIME_FIX = 155;
                VER_HIGHRES_THUMBS = 177;
            } else {
                // public release version
                VER_TRAKT_SEC_CHANGES = 129;
                VER_SUMMERTIME_FIX = 136;
                VER_HIGHRES_THUMBS = 140;
            }

            if (lastVersion < VER_TRAKT_SEC_CHANGES) {
                TraktCredentials.get(this).removeCredentials();
                editor.putString(SeriesGuidePreferences.KEY_SECURE, null);
            }
            if (lastVersion < VER_SUMMERTIME_FIX) {
                scheduleAllShowsUpdate();
            }
            if (lastVersion < VER_HIGHRES_THUMBS
                    && DisplaySettings.isVeryLargeScreen(getApplicationContext())) {
                // clear image cache
                ImageProvider.getInstance(this).clearCache();
                ImageProvider.getInstance(this).clearExternalStorageCache();
                scheduleAllShowsUpdate();
            }
            // time calculation has changed, all episodes need re-calculation
            if (lastVersion < 218) {
                // flag all episodes as outdated
                ContentValues values = new ContentValues();
                values.put(SeriesGuideContract.Episodes.LAST_EDITED, 0);
                getContentResolver().update(SeriesGuideContract.Episodes.CONTENT_URI, values, null,
                        null);
                // flag all shows outdated as well (in case the full sync is aborted, delta sync will pick up)
                scheduleAllShowsUpdate();
                // trigger full sync
                SgSyncAdapter.requestSyncImmediate(this, SgSyncAdapter.SyncType.FULL, 0, false);
            }

            // update notification
            Toast.makeText(this, R.string.updated, Toast.LENGTH_LONG).show();

            // set this as lastVersion
            editor.putInt(AppSettings.KEY_VERSION, currentVersion);

            editor.commit();
        }
    }

    private void scheduleAllShowsUpdate() {
        // force update of all shows
        ContentValues values = new ContentValues();
        values.put(Shows.LASTUPDATED, 0);
        getContentResolver().update(Shows.CONTENT_URI, values, null, null);
    }

    @Override
    public void onFirstRunDismissed() {
        // replace the first run fragment with a show fragment
        mTabsAdapter.updateTab(R.string.shows, ShowsFragment.class, null, 0);
        mTabsAdapter.notifyTabsChanged();
    }

    @Override
    protected void fireTrackerEvent(String label) {
        Utils.trackAction(this, TAG, label);
    }

    /**
     * Shows or hides a custom indeterminate progress indicator inside this activity layout.
     */
    public void setProgressVisibility(boolean isVisible) {
        mProgressBar.setVisibility(isVisible ? View.VISIBLE : View.GONE);
    }

    // Listener that's called when we finish querying the items and
    // subscriptions we own
    IabHelper.QueryInventoryFinishedListener mGotInventoryListener
            = new IabHelper.QueryInventoryFinishedListener() {
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
            Timber.d("Query inventory finished.");

            // Have we been disposed of in the meantime? If so, quit.
            if (mHelper == null) {
                return;
            }

            if (result.isFailure()) {
                // ignore failures (maybe not, requires testing)
                disposeIabHelper();
                return;
            }

            Timber.d("Query inventory was successful.");

            BillingActivity.checkForSubscription(ShowsActivity.this, inventory);

            Timber.d("Inventory query finished.");
            disposeIabHelper();
        }
    };

    private void disposeIabHelper() {
        if (mHelper != null) {
            Timber.i("Disposing of IabHelper.");
            mHelper.dispose();
        }
        mHelper = null;
    }

    /**
     * Create a new anonymous SyncStatusObserver. It's attached to the app's ContentResolver in
     * onResume(), and removed in onPause(). If a sync is active or pending, a progress bar is
     * shown.
     */
    private SyncStatusObserver mSyncStatusObserver = new SyncStatusObserver() {
        /** Callback invoked with the sync adapter status changes. */
        @Override
        public void onStatusChanged(int which) {
            runOnUiThread(new Runnable() {
                /**
                 * The SyncAdapter runs on a background thread. To update the
                 * UI, onStatusChanged() runs on the UI thread.
                 */
                @Override
                public void run() {
                    Account account = AccountUtils.getAccount(ShowsActivity.this);
                    if (account == null) {
                        // GetAccount() returned an invalid value. This
                        // shouldn't happen.
                        setProgressVisibility(false);
                        return;
                    }

                    // Test the ContentResolver to see if the sync adapter is
                    // active or pending.
                    // Set the state of the refresh button accordingly.
                    boolean syncActive = ContentResolver.isSyncActive(
                            account, SeriesGuideApplication.CONTENT_AUTHORITY);
                    boolean syncPending = ContentResolver.isSyncPending(
                            account, SeriesGuideApplication.CONTENT_AUTHORITY);
                    setProgressVisibility(syncActive || syncPending);
                }
            });
        }
    };

    /**
     * Special {@link TabStripAdapter} which saves the currently selected page to preferences, so
     * we
     * can restore it when the user comes back later.
     */
    public static class ShowsTabPageAdapter extends TabStripAdapter
            implements ViewPager.OnPageChangeListener {

        private SharedPreferences mPrefs;

        public ShowsTabPageAdapter(FragmentManager fm, Context context, ViewPager pager,
                PagerSlidingTabStrip tabs) {
            super(fm, context, pager, tabs);
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            tabs.setOnPageChangeListener(this);
        }

        @Override
        public int getItemPosition(Object object) {
            if (object instanceof FirstRunFragment) {
                return POSITION_NONE;
            } else {
                return super.getItemPosition(object);
            }
        }

        @Override
        public void onPageScrollStateChanged(int arg0) {
        }

        @Override
        public void onPageScrolled(int arg0, float arg1, int arg2) {
        }

        @Override
        public void onPageSelected(int position) {
            // save selected tab index
            mPrefs.edit().putInt(ActivitySettings.KEY_ACTIVITYTAB, position).commit();
        }
    }
}
