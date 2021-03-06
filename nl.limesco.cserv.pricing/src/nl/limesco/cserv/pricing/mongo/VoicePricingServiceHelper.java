package nl.limesco.cserv.pricing.mongo;

import java.util.Calendar;
import java.util.Collection;

import net.vz.mongodb.jackson.DBQuery;
import net.vz.mongodb.jackson.DBQuery.Query;
import nl.limesco.cserv.pricing.api.ApplicabilityFilter;
import nl.limesco.cserv.pricing.api.VoiceApplicabilityFilter;
import nl.limesco.cserv.pricing.api.VoicePricingRule;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import com.mongodb.BasicDBObject;

public class VoicePricingServiceHelper extends PricingServiceHelper<VoicePricingRule, VoicePricingRuleImpl> {
	
	protected VoicePricingServiceHelper() {
		super(VoicePricingRuleImpl.class);
	}

	public void start() {
		collection().ensureIndex(new BasicDBObject()
				.append("service", 1)
				.append("applicability.validFrom", 1)
				.append("applicability.source", 1)
				.append("applicability.callConnectivityType", 1)
				.append("applicability.cdrType", 1));
	}

	@Override
	public Optional<? extends VoicePricingRule> getPricingRuleById(String id) {
		return Optional.fromNullable(collection().findOneById(id));
	}
	
	@Override
	public Collection<? extends VoicePricingRule> getApplicablePricingRules(Calendar day, ApplicabilityFilter<VoicePricingRule> filter) {
		return getApplicablePricingRules(day, (VoiceApplicabilityFilter) filter);
	}

	public Collection<? extends VoicePricingRule> getApplicablePricingRules(Calendar day, VoiceApplicabilityFilter filter) {
		Query query = DBQuery.is("service", "voice").lessThanEquals("applicability.validFrom", day.getTime());
		
		if (filter.getSources().isPresent()) {
			query.in("applicability.source", filter.getSources().get());
		}
		
		if (filter.getCallConnectivityTypes().isPresent()) {
			query.in("applicability.callConnectivityType", filter.getCallConnectivityTypes().get());
		}
		
		if (filter.getCdrTypes().isPresent()) {
			query.in("applicability.cdrType", filter.getCdrTypes().get());
		}
		
		query.or(DBQuery.notExists("applicability.validUntil"), DBQuery.greaterThan("applicability.validUntil", day.getTime()));
		
		return Sets.newHashSet((Iterable<VoicePricingRuleImpl>) collection().find(query));
	}

}
