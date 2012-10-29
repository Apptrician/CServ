package nl.limesco.cserv.account.rest;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import nl.limesco.cserv.invoice.api.InvoiceCurrency;

public class VelocityUtils {

	private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("dd-MM-yyyy") {{
		setTimeZone(TimeZone.getTimeZone("UTC"));
	}};

	public static String formatPrice(InvoiceCurrency currency, long price) {
		StringBuilder priceStr = new StringBuilder(Long.toString(price));
		if (priceStr.length() > currency.hiddenDigits) {
			priceStr.setLength(priceStr.length() - currency.hiddenDigits);
		} else {
			priceStr.setLength(0);
			priceStr.append("0");
		}
		if (priceStr.length() > currency.fractionDigits) {
			priceStr.insert(priceStr.length() - currency.fractionDigits, ",");
		} else {
			return String.format("0,%0" + currency.fractionDigits + "d", Integer.parseInt(priceStr.toString()));
		}
		return priceStr.toString();
	}
	
	public static String formatDuration(long totalSeconds) {
		final long hours = totalSeconds / 3600;
		final long minutes = (totalSeconds / 60) % 60;
		final long seconds = totalSeconds % 60;
		return String.format("%d:%02d:%02d", hours, minutes, seconds);
	}
	
	public static String formatDate(Calendar date) {
		return DAY_FORMAT.format(date.getTime());
	}
	
}
