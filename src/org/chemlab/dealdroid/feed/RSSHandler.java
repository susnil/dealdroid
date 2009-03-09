package org.chemlab.dealdroid.feed;

import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.chemlab.dealdroid.Item;
import org.chemlab.dealdroid.Utils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.net.Uri;
import android.util.Log;

/**
 * SAX event handler which has some idea of the kind of RSS that we are
 * interested in, and creates a sorted map of Item objects.
 * 
 * @author shade
 * @version $Id$
 */
public class RSSHandler extends DefaultHandler implements FeedHandler {

	private enum ItemTag {
		TITLE, LINK, DESCRIPTION, PRICE, PUBDATE, IMAGE_LINK, SHORT_DESCRIPTION, WOOTOFF;
	}

	private boolean inItem = false;

	private ItemTag currentTag = null;

	private Item currentItem;

	private Date currentItemDate;

	private StringBuilder currentString;

	private final TreeMap<Date, Item> items = new TreeMap<Date, Item>();

	private static final String PRICE_REGEX = "Price.*\\$(\\d+\\.\\d+)";
	
	private static final String REPLACE_HTML_REGEX = "\\<.*?\\>";
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String,
	 * java.lang.String, java.lang.String, org.xml.sax.Attributes)
	 */
	@Override
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

		currentString = new StringBuilder();
		
		final String tag = localName.trim().toLowerCase(Locale.getDefault());
		
		if (tag.equals("item")) {
			inItem = true;
			currentItem = new Item();
		} else if (tag.equals("title")) {
			currentTag = ItemTag.TITLE;
		} else if (tag.equals("link")) {
			currentTag = ItemTag.LINK;
		} else if (tag.equals("description")) {
			currentTag = ItemTag.DESCRIPTION;
		} else if (tag.equals("price")) {
			currentTag = ItemTag.PRICE;
		} else if (tag.equals("pubdate")) {
			currentTag = ItemTag.PUBDATE;
		} else if (tag.equals("thumnailimage")) {
			currentTag = ItemTag.IMAGE_LINK;
		} else if (tag.equals("subtitle")) {
			currentTag = ItemTag.SHORT_DESCRIPTION;
		} else if (tag.equals("wootoff")) {
			currentTag = ItemTag.WOOTOFF;
		} else {
			currentTag = null;
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String,
	 * java.lang.String, java.lang.String)
	 */
	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {

		if (inItem && currentItem != null) {
			if (localName.trim().equals("item")) {
							
				inItem = false;
				if (currentItemDate != null) {
					final Item clone = (Item) currentItem.clone();
					items.put((Date) currentItemDate.clone(), clone);
					currentItemDate = null;
				}

			} else if (currentTag != null) {

				final String chars = currentString.toString().trim();

				if (chars != null) {
					switch (currentTag) {
					case TITLE:
						currentItem.setTitle(chars);
						break;
					case LINK:
						currentItem.setLink(Uri.parse(chars));
						break;
					case IMAGE_LINK:
						currentItem.setImageLink(Uri.parse(chars));
						break;
					case DESCRIPTION:
						currentItem.setDescription(chars);
						break;
					case PRICE:
						currentItem.setSalePrice(chars);
						break;
					case SHORT_DESCRIPTION:
						currentItem.setShortDescription(chars);
						break;
					case WOOTOFF:
						// if there is no woot-off, force an expiration
						if (chars.toLowerCase(Locale.getDefault()).equals("false")) {
							final Calendar c = Calendar.getInstance();
							c.add(Calendar.HOUR_OF_DAY, 1);
							currentItem.setExpiration(c.getTime());
						}
						break;
					case PUBDATE:
						try {
							currentItemDate = Utils.parseRFC822Date(chars);
						} catch (ParseException e) {

							// BC likes to just send "MDT" sometimes as the pubDate
							Log.e(this.getClass().getSimpleName(), "[data: " + chars + "] " + e.getMessage(), e);
						}
						break;
					}
				}

			}
		}
		currentTag = null;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
	 */
	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {

		if (inItem && currentTag != null) {
			final String chars = new String(ch).substring(start, start + length);
			if (chars.length() > 0) {
				currentString.append(chars);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.chemlab.dealdroid.feed.FeedHandler#getCurrentItem()
	 */
	@Override
	public Item getCurrentItem() {
		final Item ret = items.size() == 0 ? null : items.get(items.lastKey());
		if (ret != null && ret.getSalePrice() == null) {
			ret.setSalePrice(searchDescriptionForPrice(currentItem));
		}
		return ret;
	}

	/**
	 * @param item
	 * @return
	 */
	private static String searchDescriptionForPrice(final Item item) {
		
		String price = null;
		if (item.getDescription() != null) {

			final String cleanDesc = item.getDescription().replaceAll(REPLACE_HTML_REGEX, "");
			if (cleanDesc != null) {
				final Pattern p = Pattern.compile(PRICE_REGEX, Pattern.MULTILINE);
				final Matcher m = p.matcher(cleanDesc);
				if (m.find()) {
					final String spp = m.group().trim();
					final String[] sp = spp.split("\\$");
					if (sp.length == 2) {
						price = sp[1];
					}
				}
			}
		}
		
		return price;
	}
	
}
