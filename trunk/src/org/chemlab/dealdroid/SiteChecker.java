package org.chemlab.dealdroid;

import static android.content.Context.NOTIFICATION_SERVICE;
import static org.chemlab.dealdroid.Preferences.KEEP_AWAKE;
import static org.chemlab.dealdroid.Preferences.PREFS_NAME;
import static org.chemlab.dealdroid.Preferences.isAnySiteEnabled;
import static org.chemlab.dealdroid.Preferences.isEnabled;

import java.io.InputStream;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.AllClientPNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.chemlab.dealdroid.feed.FeedHandler;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.util.Xml;
import android.util.Xml.Encoding;

/**
 * BroadcastReceiver that deals with various Intents, such as updating sites,
 * managing the alarms.  The actual checkers will run in separate threads, and
 * take care of acquiring WakeLocks while running.  Notifications are sent when
 * new items appear, and clicking on these notifications launches an ItemViewer.
 * 
 * @author shade
 * @version $Id: DealDroidSiteChecker.java 15 2009-02-16 17:06:44Z steve.kondik$
 */
public class SiteChecker extends BroadcastReceiver {

	public static final String BOOT_INTENT = "android.intent.action.BOOT_COMPLETED";

	public static final String DEALDROID_START = "org.chemlab.dealdroid.DEALDROID_START";

	public static final String DEALDROID_STOP = "org.chemlab.dealdroid.DEALDROID_STOP";

	public static final String DEALDROID_RESTART = "org.chemlab.dealdroid.DEALDROID_RESTART";

	public static final String DEALDROID_UPDATE = "org.chemlab.dealdroid.DEALDROID_UPDATE";

	public static final String DEALDROID_ENABLE = "org.chemlab.dealdroid.DEALDROID_ENABLE";

	public static final String DEALDROID_DISABLE = "org.chemlab.dealdroid.DEALDROID_DISABLE";

	private static final long UPDATE_INTERVAL = 180000;

	

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context,
	 * android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {

		Log.d(this.getClass().getName(), "Intent: " + intent.getAction());

			if (DEALDROID_ENABLE.equals(intent.getAction())) {
				final Site site = Site.valueOf(intent.getExtras().getString("site"));
				if (site != null) {
					checkSites(context, site);
				}

			} else if (DEALDROID_DISABLE.equals(intent.getAction())) {
				final Site site = Site.valueOf(intent.getExtras().getString("site"));
				if (site != null) {
					disableSite(context, site);
				}

			} else if (BOOT_INTENT.equals(intent.getAction()) || DEALDROID_START.equals(intent.getAction())) {
				disable(context);
				enable(context);

			} else if (DEALDROID_STOP.equals(intent.getAction())) {
				disable(context);

			} else if (DEALDROID_RESTART.equals(intent.getAction())) {
				disable(context);
				enable(context);

			} else if (DEALDROID_UPDATE.equals(intent.getAction())) {
				checkSites(context, Site.values());
			}

	}

	/**
	 * @param site
	 */
	private void disableSite(final Context context, final Site site) {
		Log.i(this.getClass().getSimpleName(), "Deleting data for site: " + site.toString());
		final Database db = new Database(context);
		db.open();
		db.delete(site);
		db.close();
	}

	/**
	 * Checks the given sites for new items (only if the network is up).
	 * 
	 * @param context
	 */
	private void checkSites(final Context context, final Site... sites) {

		final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		final NetworkInfo info = cm.getActiveNetworkInfo();

		if (info != null && info.isAvailable()) {

			final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
			if (isAnySiteEnabled(prefs)) {
				for (Site site : sites) {
					if (isEnabled(prefs, site)) {
						final Thread checker = new SiteCheckerThread(context, site);
						checker.setDaemon(true);
						checker.start();
					}
				}
			}
		}
	}

	/**
	 * @param context
	 */
	private synchronized void enable(final Context context) {
		Log.i(this.getClass().getSimpleName(), "Starting DealDroid updater..");

		final int mode = shouldKeepPhoneAwake(context) ? AlarmManager.ELAPSED_REALTIME_WAKEUP
				: AlarmManager.ELAPSED_REALTIME;
		getAlarmManager(context).setRepeating(mode, 0, UPDATE_INTERVAL, getSiteCheckerIntent(context));
	}

	/**
	 * @param context
	 */
	private synchronized void disable(final Context context) {
		Log.i(this.getClass().getSimpleName(), "Stopping DealDroid updater..");
		getAlarmManager(context).cancel(getSiteCheckerIntent(context));
	}

	/**
	 * @param context
	 * @return
	 */
	private static AlarmManager getAlarmManager(final Context context) {
		return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
	}

	/**
	 * @param context
	 * @return
	 */
	private static PendingIntent getSiteCheckerIntent(final Context context) {
		return PendingIntent.getBroadcast(context, 0, new Intent(SiteChecker.DEALDROID_UPDATE), 0);
	}

	/**
	 * @param context
	 * @return
	 */
	private static boolean shouldKeepPhoneAwake(final Context context) {
		final SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		return preferences.getBoolean(KEEP_AWAKE, false);
	}

	/**
	 * @author shade
	 * 
	 */
	private static class SiteCheckerThread extends Thread {

		private final Context context;
		private final Database database;
		private final SharedPreferences preferences;
		private final WakeLock wakeLock;
		private final HttpClient httpClient = new DefaultHttpClient();

		private final Site site;

		SiteCheckerThread(final Context context, final Site site) {
			this.context = context;
			this.site = site;
			this.database = new Database(context);
			this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
			this.httpClient.getParams().setIntParameter(AllClientPNames.CONNECTION_TIMEOUT, 10000);
			this.httpClient.getParams().setIntParameter(AllClientPNames.SO_TIMEOUT, 10000);
			
			final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
			this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DealDroid");
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Thread#run()
		 */
		@Override
		public void run() {

			// Get the WakeLock, open the Database, do our job and clean up.
			if (wakeLock != null) {
				wakeLock.acquire();
			}

			try {
				database.open();
				checkSites();
			} finally {
				database.close();
				if (wakeLock != null) {
					wakeLock.release();
				}
			}
		}

		/**
		 * @param context
		 */
		private void checkSites() {

			Log.d(this.getClass().getSimpleName(), "Handling " + site);

			try {

				final HttpGet req = new HttpGet(site.getUrl().toURI());
				
				// Make sure we bypass caches
				req.addHeader("Cache-Control", "no-cache");
				req.addHeader("Pragma", "no-cache");

				final HttpResponse response = httpClient.execute(req);

				if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {

					final FeedHandler handler = site.getHandler().newInstance();

					final InputStream in = response.getEntity().getContent();
					Xml.parse(in, Encoding.UTF_8, handler);
					in.close();

					notify(site, handler.getCurrentItem());

				} else {
					Log.e(this.getClass().getSimpleName(), "HTTP request failed: " + response.getStatusLine().toString());
				}

			} catch (Exception e) {

				Log.e(this.getClass().getSimpleName(), e.getMessage(), e);
			}

		}

		/**
		 * @param site
		 * @param item
		 */
		private synchronized void notify(final Site site, final Item item) {

			if (item != null && item.getTitle() != null) {

				if (database.updateStateIfNotCurrent(site, item)) {

					Log.i(this.getClass().getSimpleName(), "Creating new notification for " + site.name());
					((NotificationManager) context.getSystemService(NOTIFICATION_SERVICE)).notify(site.ordinal(),
							createNotification(site, item));

				} else {
					Log.d(this.getClass().getSimpleName(), "Not creating notification.");
				}
			} else {
				Log.e(this.getClass().getName(), "Incomplete item object, not notifying.");
			}
		}

		/**
		 * @param site
		 * @param item
		 * @param context
		 * @return
		 */
		private Notification createNotification(final Site site, final Item item) {

			final Notification notification = new Notification(site.getDrawable(), item.getTitle(), System
					.currentTimeMillis());

			final Uri link;
			if (site.getAffiliationKey() == null) {
				link = item.getLink();
			} else {
				link = item.getLink().buildUpon().appendQueryParameter(site.getAffiliationKey(),
						site.getAffiliationValue()).build();
			}
			
			final Intent i = new Intent(context, ItemViewer.class);
			i.setData(link);
			i.putExtra("site", site.name());
			
			final PendingIntent contentIntent = PendingIntent.getActivity(context, 0, i, 0);

			final String priceInfo = "$" + item.getSalePrice() + " (" + item.getSavings() + "% Off! Regularly: " + item.getRetailPrice() + ")";
			notification.setLatestEventInfo(context, item.getTitle(), priceInfo, contentIntent);

			notification.flags = notification.flags | Notification.FLAG_AUTO_CANCEL;

			// Notification options
			if (preferences.getBoolean(Preferences.NOTIFY_VIBRATE, false)) {
				notification.vibrate = new long[] { 100, 250, 100, 500 };
			}

			if (preferences.getBoolean(Preferences.NOTIFY_LED, false)) {
				notification.ledARGB = 0xFFFF5171;
				notification.ledOnMS = 500;
				notification.ledOffMS = 500;
				notification.flags = notification.flags | Notification.FLAG_SHOW_LIGHTS;
			}

			return notification;

		}
	}
}
