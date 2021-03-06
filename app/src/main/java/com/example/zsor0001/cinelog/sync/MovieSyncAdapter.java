package com.example.zsor0001.cinelog.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.text.format.Time;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.zsor0001.cinelog.MainActivity;
import com.example.zsor0001.cinelog.R;
import com.example.zsor0001.cinelog.data.MovieContract;

import static java.lang.System.currentTimeMillis;

public class MovieSyncAdapter extends AbstractThreadedSyncAdapter {

    public static final String LOG_TAG = MovieSyncAdapter.class.getSimpleName();

    //Interval at which to sync with the weather, in milliseconds.
    //60 seconds (1 minute) 180 = 3 hours
    public static final int SYNC_INTERVAL = 60 * 180;
    public static final int SYNC_FLEXTIME = SYNC_INTERVAL / 3;
    private static final long DAY_IN_MILLIS = 1000 * 60 * 60 * 24;
    private static final int MOVIE_NOTIFICATION_ID = 3004;

    private static final String[] NOTIFY_MOVIE_PROJECTION = new String[] {
            MovieContract.MovieEntry.TABLE_NAME + "." + MovieContract.MovieEntry._ID,
            MovieContract.MovieEntry.COLUMN_TITLE,
            MovieContract.MovieEntry.COLUMN_OVERVIEW,
            MovieContract.MovieEntry.COLUMN_POPULARITY,
            MovieContract.MovieEntry.COLUMN_RELEASE_DATE,
            MovieContract.MovieEntry.COLUMN_RATING,
            MovieContract.MovieEntry.COLUMN_POSTER_PATH,
            MovieContract.MovieEntry.COLUMN_DATE
    };

    static final int COL_MOVIE_ID = 0;
    static final int COL_MOVIE_TITLE = 1;
    static final int COL_MOVIE_OVERVIEW = 2;
    static final int COL_MOVIE_POPULARITY = 3;
    static final int COL_MOVIE_RELEASE = 4;
    static final int COL_MOVIE_RATING = 5;
    static final int COL_MOVIE_POSTER = 6;
    static final int COL_MOVIE_DATE = 7;

    public MovieSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.d(LOG_TAG, "Starting Sync.");

        // These two need to be declared outside the try/catch
        // so that they can be closed in the finally block.
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String moviesJsnStr = null;

        String sort_by = "popularity.desc";
        String appID = "b2af4d3650a777ceaaaa5199cf1e6458";

        try {
            // Construct the URL for the TheMovieDb query
            final String FORECAST_BASE_URL =
                    "http://api.themoviedb.org/3/discover/movie?";
            final String SORT_PARAM = "sort_by";
            final String APPID_PARAM = "api_key";
            final String PAGE = "page";

            for(int i = 1; i<5; i++) {

                String pageNo = Integer.toString(i);

                Uri builtUri = Uri.parse(FORECAST_BASE_URL).buildUpon()
                        .appendQueryParameter(SORT_PARAM, sort_by)
                        .appendQueryParameter(APPID_PARAM, appID)
                        .appendQueryParameter(APPID_PARAM, appID)
                        .appendQueryParameter(PAGE, pageNo)
                        .build();

                URL url = new URL(builtUri.toString());

                // Create the request to TheMovieDb, and open the connection
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();

                // Read the input stream into a String
                InputStream inputStream = urlConnection.getInputStream();
                StringBuffer buffer = new StringBuffer();
                if (inputStream == null) {
                    // Nothing to do.
                    return;
                }
                reader = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                while ((line = reader.readLine()) != null) {
                    // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                    // But it does make debugging a *lot* easier if you print out the completed
                    // buffer for debugging.
                    buffer.append(line + "\n");
                }

                if (buffer.length() == 0) {
                    // Stream was empty.  No point in parsing.
                    return;
                }
                moviesJsnStr = buffer.toString();
                getMovieDataFromJson(moviesJsnStr);
            }

        } catch (IOException e) {
            Log.e(LOG_TAG, "Error ", e);
            // If the code didn't successfully get the movie data, there's no point in attemping
            // to parse it.
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, "Error closing stream", e);
                }
            }
        }
        // This will only happen if there was an error getting or parsing the data.
        return;
    }

    /**
     * Take the String representing the complete movie in JSON Format and
     * pull out the data we need to construct the Strings needed for the wireframes.
     * Fortunately parsing is easy:  constructor takes the JSON string and converts it
     * into an Object hierarchy for us.
     */
    private void getMovieDataFromJson(String forecastJsonStr)
            throws JSONException {


        // These are the names of the JSON objects that need to be extracted.
        final String OWM_LIST = "results";
        final String OWM_TITLE = "title";
        final String OWM_OVERVIEW = "overview";
        final String OWM_RELEASE = "release_date";
        final String OWM_VOTE_AVERAGE = "vote_average";
        final String OWM_PATH = "poster_path";
        final String OWM_POPULARITY = "popularity";

        try {
            JSONObject forecastJson = new JSONObject(forecastJsonStr);
            JSONArray movieArray = forecastJson.getJSONArray(OWM_LIST);

            // Insert the new movie information into database
            Vector<ContentValues> cVVector = new Vector<>(movieArray.length());

            Time dayTime = new Time();
            dayTime.setToNow();

            // we start at the day returned by local time. Otherwise this is a mess.
            int julianStartDay = Time.getJulianDay(currentTimeMillis(), dayTime.gmtoff);

            // now we work exclusively in UTC
            dayTime = new Time();

            for (int i = 0; i < movieArray.length(); i++) {

                // Get the JSON object representing the movie
                JSONObject movieMain = movieArray.getJSONObject(i);

                String title = movieMain.getString(OWM_TITLE);
                String overview = movieMain.getString(OWM_OVERVIEW);
                String release_date = movieMain.getString(OWM_RELEASE);
                double vote_average = movieMain.getDouble(OWM_VOTE_AVERAGE);
                double popularity = movieMain.getDouble(OWM_POPULARITY);


                // Construct the URL for the TheMovieDb query
                final String baseUrl =
                        "http://image.tmdb.org/t/p/w185";
                final String posterPath = movieMain.getString(OWM_PATH);

                String posterURL = baseUrl.concat(posterPath);


                long date = dayTime.setJulianDay(julianStartDay);

                ContentValues movieValues = new ContentValues();

                movieValues.put(MovieContract.MovieEntry.COLUMN_OVERVIEW, overview);
                movieValues.put(MovieContract.MovieEntry.COLUMN_RELEASE_DATE, release_date);
                movieValues.put(MovieContract.MovieEntry.COLUMN_TITLE, title);
                movieValues.put(MovieContract.MovieEntry.COLUMN_RATING, vote_average);
                movieValues.put(MovieContract.MovieEntry.COLUMN_POSTER_PATH, posterURL);
                movieValues.put(MovieContract.MovieEntry.COLUMN_POPULARITY, popularity);
                movieValues.put(MovieContract.MovieEntry.COLUMN_DATE, date);

                cVVector.add(movieValues);
            }


            int inserted = 0;

            if (cVVector.size() > 0) {

                ContentValues[] cvArray = new ContentValues[cVVector.size()];
                cVVector.toArray(cvArray);
                getContext().getContentResolver().bulkInsert(MovieContract.MovieEntry.CONTENT_URI, cvArray);

                // delete old data so we don't build up an endless history

                getContext().getContentResolver().delete(MovieContract.MovieEntry.CONTENT_URI,
                        MovieContract.MovieEntry.COLUMN_DATE + " <= ?",
                        new String[] { Long.toString((dayTime.setJulianDay(julianStartDay-1)))});

               notifyMovies();
            }

            /*

            String sortOrder = MovieContract.MovieEntry.COLUMN_POPULARITY + " DESC";
            Uri movieUri = MovieContract.MovieEntry.CONTENT_URI;

            Cursor cur = mContext.getContentResolver().query(movieUri, null, null,
                    null, sortOrder);

            cVVector = new Vector<>(cur.getCount());
            if (cur.moveToFirst()) {
                do {
                    ContentValues cv = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cur, cv);
                    cVVector.add(cv);
                } while (cur.moveToNext());
            }
            */

            Log.d(LOG_TAG, "Movies service complete. " + cVVector.size() + " Inserted");

        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
        }
    }

    private void notifyMovies() {

        Context context = getContext();

        //checking the last update and notify if it' the first of the day
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String displayNotificationsKey = context.getString(R.string.pref_enable_notifications_key);
        boolean displayNotifications = prefs.getBoolean(displayNotificationsKey,
                Boolean.parseBoolean(context.getString(R.string.pref_enable_notifications_default)));

        if (displayNotifications) {

            String lastNotificationKey = context.getString(R.string.pref_last_notification);
            long lastSync = prefs.getLong(lastNotificationKey, 0);

            if (currentTimeMillis() - lastSync >= DAY_IN_MILLIS) {
                // Last sync was more than 1 day ago, let's send a notification with the movies.

                Uri movieUri = MovieContract.MovieEntry.buildMoviesWithDate(System.currentTimeMillis());

                // we'll query our contentProvider, as always
                Cursor cursor = context.getContentResolver().query(movieUri, NOTIFY_MOVIE_PROJECTION, null, null, null);
                if (cursor.moveToFirst()) {
                    String title = cursor.getString(COL_MOVIE_TITLE);
                    String overview = cursor.getString(COL_MOVIE_OVERVIEW);
                    String rating = cursor.getString(COL_MOVIE_RATING);
                    String release = cursor.getString(COL_MOVIE_RELEASE);
                    String poster = cursor.getString(COL_MOVIE_POSTER);

                    // NotificationCompatBuilder is a very convenient way to build backward-compatible
                    // notifications.  Just throw in some data.
                    NotificationCompat.Builder mBuilder =
                            new NotificationCompat.Builder(getContext())
                                    .setContentTitle(title)
                                    .setContentText(overview);

                    // Make something interesting happen when the user clicks on the notification.
                    // In this case, opening the app is sufficient.
                    Intent resultIntent = new Intent(context, MainActivity.class);

                    // The stack builder object will contain an artificial back stack for the
                    // started Activity.
                    // This ensures that navigating backward from the Activity leads out of
                    // your application to the Home screen.
                    TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
                    stackBuilder.addNextIntent(resultIntent);
                    PendingIntent resultPendingIntent =
                            stackBuilder.getPendingIntent(
                                    0,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                            );
                    mBuilder.setContentIntent(resultPendingIntent);

                    NotificationManager mNotificationManager =
                            (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
                    // MOVIE_NOTIFICATION_ID allows you to update the notification later on.
                    mNotificationManager.notify(MOVIE_NOTIFICATION_ID, mBuilder.build());

                    //refreshing last sync
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong(lastNotificationKey, System.currentTimeMillis());
                    editor.commit();
                }
                cursor.close();
            }

        }
    }

    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Log.d(LOG_TAG, "in configurePeriodicSync");
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our periodic sync
            SyncRequest request = new SyncRequest.Builder().
                    syncPeriodic(syncInterval, flexTime).
                    setSyncAdapter(account, authority).
                    setExtras(new Bundle()).build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                    authority, new Bundle(), syncInterval);
        }
    }

    /*
     * Helper method to have the sync adapter sync immediately
     * @param context The context used to access the account service

    public static void syncImmediately(Context context) {
        Log.d(LOG_TAG, "in sync immediately");
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                context.getString(R.string.content_authority), bundle);
    }
    */

    /**
     * Helper method to get the fake account to be used with SyncAdapter, or make a new one
     * if the fake account doesn't exist yet.  If we make a new account, we call the
     * onAccountCreated method so we can initialize things.
     * @param context The context used to access the account service
     * @return a fake account.
     */
    public static Account getSyncAccount(Context context) {

        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name), context.getString(R.string.sync_account_type));


        // If the password doesn't exist, the account doesn't exist
        if (accountManager.getPassword(newAccount) == null) {
            Log.d(LOG_TAG, "in get account " + accountManager.getPassword(newAccount));

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call ContentResolver.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */
            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }

    private static void onAccountCreated(Account newAccount, Context context) {
        /*
         * Since we've created an account
         */
        MovieSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

        /*
         * Without calling setSyncAutomatically, our periodic sync will not be enabled.
         */
        ContentResolver.setSyncAutomatically(newAccount, context.getString(R.string.content_authority), true);

        /*
         * Finally, let's do a sync to get things started
         */
        //syncImmediately(context);

    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }
}