package nl.limesco.cserv.invoice.rest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import nl.limesco.cserv.account.api.Account;
import nl.limesco.cserv.invoice.api.BatchInvoicingService;
import nl.limesco.cserv.invoice.api.IdAllocationException;
import nl.limesco.cserv.invoice.api.Invoice;
import nl.limesco.cserv.invoice.api.InvoiceConstructor;
import nl.limesco.cserv.invoice.api.InvoiceService;
import nl.limesco.cserv.invoice.api.InvoiceTransformationService;
import nl.limesco.cserv.invoice.api.PhoneNumberMissingException;
import nl.limesco.cserv.invoice.api.QueuedItemLine;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;

public class InvoiceResource {
	
	private final InvoiceService invoiceService;
	
	private final InvoiceTransformationService invoiceTransformationService;
	
	private final InvoiceConstructor invoiceConstructor;
	
	private final BatchInvoicingService batchInvoicingService;
	
	private final Account account;
	
	private final boolean admin;
	
	private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("dd-MM-yyyy") {{
		setTimeZone(TimeZone.getTimeZone("UTC"));
	}};
	
	public InvoiceResource(InvoiceService invoiceService, InvoiceTransformationService invoiceTransformationService, InvoiceConstructor invoiceConstructor, BatchInvoicingService batchInvoicingService, Account account, boolean admin) {
		this.invoiceService = invoiceService;
		this.invoiceTransformationService = invoiceTransformationService;
		this.invoiceConstructor = invoiceConstructor;
		this.batchInvoicingService = batchInvoicingService;
		this.account = account;
		this.admin = admin;
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public List<SummarizedInvoice> getInvoices() {
		final List<SummarizedInvoice> summarizedInvoices = Lists.newArrayList();
		for (Invoice invoice : invoiceService.getInvoicesByAccountId(account.getId())) {
			if (!invoice.isSound()) {
				throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
			}
			summarizedInvoices.add(new SummarizedInvoice(invoice));
		}
		
		return summarizedInvoices;
	}
	
	@GET
	@Path("{invoiceId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getInvoiceByIdAsJson(@PathParam("invoiceId") String id) {
		return invoiceTransformationService.transformToJson(getInvoiceByIdForRest(id));
	}

	@GET
	@Path("{invoiceId}")
	@Produces("application/x-tex")
	public String getInvoiceByIdAsTex(@PathParam("invoiceId") String id) {
		return invoiceTransformationService.transformToTex(getInvoiceByIdForRest(id), account);
	}

	@GET
	@Path("{invoiceId}")
	@Produces("application/pdf")
	public byte[] getInvoiceByIdAsPdf(@PathParam("invoiceId") String id) {
		return invoiceTransformationService.transformToPdf(getInvoiceByIdForRest(id), account);
	}
	
	@GET
	@Path("queuedItemLines")
	@Produces(MediaType.APPLICATION_JSON)
	public Collection<? extends QueuedItemLine> getQueuedItemLines() {
		if (!admin) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		return invoiceService.getQueuedItemLinesByAccountId(account.getId());
	}
	
	@POST
	@Path("queueItemLine")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response queueItemLine(String body) {
		if(!admin) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		try {
			QueuedItemLine itemLine = invoiceService.createQueuedItemLineFromJson(body);
			invoiceService.addQueuedItemLineToAccountId(itemLine);
			return Response.status(Status.ACCEPTED).build();
		} catch (JsonParseException e) {
			throw new WebApplicationException(e);
		} catch (JsonMappingException e) {
			throw new WebApplicationException(e);
		} catch (IOException e) {
			throw new WebApplicationException(e);
		}
	}
	
	private Invoice getInvoiceByIdForRest(String id) {
		final Optional<? extends Invoice> optionalInvoice = invoiceService.getInvoiceById(id);
		if (!optionalInvoice.isPresent()) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		
		final Invoice invoice = optionalInvoice.get();
		if (!invoice.getAccountId().equals(account.getId())) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		
		if (!invoice.isSound()) {
			throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
		}
		return invoice;
	}
	
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response createNewInvoice(String json) {
		if (!admin) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		
		try {
			batchInvoicingService.computePricingForUnpricedCdrs();
			
			boolean dry_run;
			ObjectMapper om = new ObjectMapper();
			Map<String,String> req = om.readValue(json, Map.class);
			
			Calendar day = Calendar.getInstance();
			day.setTime(DAY_FORMAT.parse(req.get("date")));
			dry_run = !req.get("dry-run").equals("false");
			
			final Invoice invoice = invoiceConstructor.constructInvoiceForAccount(day, account.getId(), dry_run);
			assert(invoice.getAccountId().equals(account.getId()));
			if(dry_run) {
				assert(invoice.getId() == null);
				return Response.ok(invoiceTransformationService.transformToJson(invoice), "application/json").build();
			}
			if(invoice.getId() == null) {
				return Response.noContent().build();
			}
			return Response.created(new URI("/accounts/" + account.getId() + "/invoices/" + invoice.getId())).build();
		} catch (IOException e) {
			throw new WebApplicationException(e);
		} catch (URISyntaxException e) {
			throw new WebApplicationException(e);
		} catch (IdAllocationException e) {
			throw new WebApplicationException(e, Status.CONFLICT);
		} catch (ParseException e) {
			throw new WebApplicationException(e);
		} catch (PhoneNumberMissingException e) {
			throw new RuntimeException(e.getMessage());
		}
	}
	
}
