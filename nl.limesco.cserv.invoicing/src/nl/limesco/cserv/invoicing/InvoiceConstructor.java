package nl.limesco.cserv.invoicing;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.UUID;

import nl.limesco.cserv.cdr.api.Cdr;
import nl.limesco.cserv.cdr.api.CdrService;
import nl.limesco.cserv.invoice.api.IdAllocationException;
import nl.limesco.cserv.invoice.api.Invoice;
import nl.limesco.cserv.invoice.api.InvoiceBuilder;
import nl.limesco.cserv.invoice.api.InvoiceCurrency;
import nl.limesco.cserv.invoice.api.InvoiceService;
import nl.limesco.cserv.pricing.api.Pricing;
import nl.limesco.cserv.pricing.api.PricingRule;
import nl.limesco.cserv.pricing.api.PricingService;
import nl.limesco.cserv.sim.api.MonthedInvoice;
import nl.limesco.cserv.sim.api.Sim;
import nl.limesco.cserv.sim.api.SimService;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;

public class InvoiceConstructor {

	private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("dd-MM-yyyy") {{
		setTimeZone(TimeZone.getTimeZone("UTC"));
	}};

	// TODO: Don't use hardcoded prices (issue #42)
	private static final long ACTIVATION_PRICE = 347107;
	
	private static final long CONTRIBUTION_PRICE = 41322;
	
	private volatile InvoiceService invoiceService;

	private volatile PricingService pricingService;
	
	private volatile CdrService cdrService;
	
	private volatile SimService simService;

	public Invoice constructInvoiceForAccount(Calendar day, String accountId) throws IdAllocationException {
		
		final UUID builderUUID = UUID.randomUUID();
		
		// Compute end of subscription period
		final Calendar endOfSubscriptionPeriod = (Calendar) day.clone();
		endOfSubscriptionPeriod.set(Calendar.DAY_OF_MONTH, 1);
		endOfSubscriptionPeriod.add(Calendar.MONTH, 1);
		endOfSubscriptionPeriod.add(Calendar.DAY_OF_MONTH, -1);
		
		// Collect everything we need
		final Collection<? extends Sim> simActivations = simService.getActivatedSimsWithoutActivationInvoiceByOwnerAccountId(accountId);
		final Collection<? extends Sim> subscriptionFees = simService.getActivatedSimsLastInvoicedBeforeByOwnerAccountId(day, accountId);
		final Collection<? extends Cdr> cdrs = cdrService.getUninvoicedCdrsForAccount(accountId, builderUUID.toString());
		
		// Start building the invoice
		final InvoiceBuilder builder = invoiceService.buildInvoice()
				.accountId(accountId)
				.creationDate(day)
				.currency(InvoiceCurrency.EUR);
		
		// Include the activations
		if (!simActivations.isEmpty()) {
			builder.normalItemLine("Activatie SIM-kaart", simActivations.size(), ACTIVATION_PRICE, 0.21);
		}
		
		// Include the subscription fees
		int numberOfMonthForCostContribution = 0;
		final Map<SubscriptionKey, Integer> subscriptions = Maps.newHashMap();
		for (Sim sim : subscriptionFees) {
			final Optional<Calendar> contractStartDate = sim.getContractStartDate();
			if (!contractStartDate.isPresent()) {
				continue;
			}
			
			final Optional<MonthedInvoice> lastMonthlyFeesInvoice = sim.getLastMonthlyFeesInvoice();
			final Calendar itemStart;
			if (lastMonthlyFeesInvoice.isPresent()) {
				final Calendar monthStart = Calendar.getInstance();
				monthStart.setTimeZone(TimeZone.getTimeZone("UTC"));
				monthStart.setTimeInMillis(0);
				monthStart.set(Calendar.YEAR, lastMonthlyFeesInvoice.get().getYear());
				monthStart.set(Calendar.MONTH, lastMonthlyFeesInvoice.get().getMonth());
				itemStart = monthStart;
			} else {
				itemStart = contractStartDate.get();
			}
			
			Calendar start = (Calendar) itemStart.clone();
			while (true) {
				final Calendar end = (Calendar) start.clone();
				end.set(Calendar.DAY_OF_MONTH, 1);
				end.add(Calendar.MONTH, 1);
				end.add(Calendar.DAY_OF_MONTH, -1);
				
				final int days = 1 + (int) ((end.getTimeInMillis() - start.getTimeInMillis()) / (24 * 60 * 60 * 1000));
				final SubscriptionKey key = new SubscriptionKey(start, days, sim.getApnType());
				
				if (subscriptions.containsKey(key)) {
					subscriptions.put(key, Integer.valueOf(subscriptions.get(key).intValue() + 1));
				} else {
					subscriptions.put(key, Integer.valueOf(1));
				}
				
				if (!sim.isExemptFromCostContribution()) {
					numberOfMonthForCostContribution++;
				}
				
				if (!end.before(endOfSubscriptionPeriod)) {
					break;
				} else {
					start = (Calendar) end.clone();
					start.add(Calendar.DAY_OF_MONTH, 1);
				}
			}
		}
		
		for (Entry<SubscriptionKey, Integer> subscription : subscriptions.entrySet()) {
			final String formattedStart = DAY_FORMAT.format(subscription.getKey().getStart().getTime());
			final Calendar end = (Calendar) subscription.getKey().getStart();
			end.add(Calendar.DATE, subscription.getKey().getDays() - 1);
			final String formattedEnd = DAY_FORMAT.format(end.getTime());
			final String formattedApnType = subscription.getKey().getApnType().getFriendlyName();
			final long monthlyPrice = subscription.getKey().getApnType().getMonthlyPrice();
			final long itemPrice = computePartialMonthPrice(end.get(Calendar.DAY_OF_MONTH), subscription.getKey().getDays(), monthlyPrice);
			final String description = String.format("Vaste kosten %s - %s (%s)", formattedStart, formattedEnd, formattedApnType);
			builder.normalItemLine(description, subscription.getValue().intValue(), itemPrice, 0.21);
		}
		
		// Include the CDRs
		final Map<String, CombinedDuration> durations = Maps.newHashMap();
		for (Cdr cdr : cdrs) {
			final Optional<Cdr.Pricing> pricing = cdr.getPricing();
			if (!pricing.isPresent()) {
				continue;
			}
			
			final String pricingRuleId = pricing.get().getPricingRuleId();
			if (!durations.containsKey(pricingRuleId)) {
				durations.put(pricingRuleId, new CombinedDuration());
			}
			durations.get(pricingRuleId).addCdr(cdr);
		}
		
		for (Entry<String, CombinedDuration> duration : durations.entrySet()) {
			final Optional<? extends PricingRule> pricingRule = pricingService.getPricingRuleById(duration.getKey());
			if (!pricingRule.isPresent()) {
				continue;
			}
			
			final Pricing price = pricingRule.get().getPrice();
			if (pricingRule.get().isHidden() && price.getPerCall() == 0 && price.getPerMinute() == 0) {
				continue;
			}
			
			final CombinedDuration cd = duration.getValue();
			builder.durationItemLine("Bellen " + pricingRule.get().getDescription(), price.getPerCall(), price.getPerMinute(), cd.getCount(), cd.getSeconds(), 0.21);
		}
		
		// Include the cost contribution
		if (numberOfMonthForCostContribution > 0) {
			builder.normalItemLine("Bijdrage vaste kosten", numberOfMonthForCostContribution, CONTRIBUTION_PRICE, 0.21);
		}
		
		final Invoice invoice = builder.build();
		invoiceService.storeInvoice(invoice);
		
		for (Sim sim : simActivations) {
			sim.setActivationInvoiceId(invoice.getId());
			simService.storeActivationInvoiceId(sim);
		}
		
		for (Sim sim : subscriptionFees) {
			sim.setLastMonthlyFeesInvoice(new MonthedInvoice(day.get(Calendar.YEAR), day.get(Calendar.MONTH), invoice.getId()));
			simService.storeLastMonthlyFeesInvoice(sim);
		}
		
		cdrService.setInvoiceIdForBuilder(builderUUID.toString(), invoice.getId());
		
		return invoice;
	}

	private static long computePartialMonthPrice(int daysInMonth, int days, long monthlyPrice) {
		final BigDecimal daysInMonthBD = BigDecimal.valueOf(daysInMonth);
		final BigDecimal daysBD = BigDecimal.valueOf(days);
		final BigDecimal monthlyPriceBD = BigDecimal.valueOf(monthlyPrice);
		return monthlyPriceBD.multiply(daysBD).divideToIntegralValue(daysInMonthBD).longValue();
	}

}
