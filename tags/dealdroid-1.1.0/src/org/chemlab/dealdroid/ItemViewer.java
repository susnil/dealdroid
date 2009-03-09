package org.chemlab.dealdroid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebView;

/**
 * A simple viewer to quickly view new items before launching
 * the full browser.
 * 
 * @author shade
 * @version $Id$
 */
public class ItemViewer extends Activity {

	private WebView webview;

	private Site currentSite;
	
	private final List<Integer> keyBuffer = new ArrayList<Integer>(4);
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onCreate(android.os.Bundle)
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		currentSite = Site.valueOf(getIntent().getExtras().getString("site"));

		webview = (WebView) findViewById(R.id.webview);
		
		// If we have a custom asset for the site, use it.  Otherwise just go to the page.
		// Sorry, I am not doing custom assets unless there is an affiliation.
		if (hasSiteAsset(currentSite)) {
			webview.loadUrl("content://org.chemlab.dealdroid/" + currentSite.name());
		} else {
			
			final Database db = new Database(this);
			try {
				db.open();
				webview.loadUrl(currentSite.applyAffiliation(db.getCurrentItem(currentSite).getLink()).toString());
			} finally {
				db.close();
			}
		}

	}

	/**
	 * Do we have a custom template for the site preview?
	 * 
	 * @param site
	 * @return
	 */
	private boolean hasSiteAsset(final Site site) {
		boolean ret = false;
		final String siteAsset = site.name().toLowerCase() + ".html";
		try {
			for (String asset : this.getAssets().list("")) {
				if (siteAsset.equals(asset)) {
					ret = true;
					break;
				}
			}
		} catch (IOException e) {
			Log.e(this.getClass().getSimpleName(), e.getMessage(), e);
		}
		return ret;
	}
	
	/* (non-Javadoc)
	 * @see android.app.Activity#onKeyDown(int, android.view.KeyEvent)
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		
		// There is 1337 shit happening here, and I am bored.
		keyBuffer.add(keyCode);
		
		if (keyBuffer.size() == 4) {
			if (keyBuffer.get(0) == 35 && keyBuffer.get(1) == 42 && keyBuffer.get(2) == 29 && keyBuffer.get(3) == 46) {
				keyBuffer.clear();
				if (webview != null) {
					webview.loadUrl("http://n0rp.chemlab.org/dd/tita.html");
				}
			} else {
				keyBuffer.remove(0);
			}
		}
	
		return super.onKeyDown(keyCode, event);
	}

	
}