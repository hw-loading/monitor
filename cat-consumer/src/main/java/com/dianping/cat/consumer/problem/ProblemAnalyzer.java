package com.dianping.cat.consumer.problem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;

import com.dianping.cat.consumer.problem.handler.Handler;
import com.dianping.cat.consumer.problem.model.entity.JavaThread;
import com.dianping.cat.consumer.problem.model.entity.Machine;
import com.dianping.cat.consumer.problem.model.entity.ProblemReport;
import com.dianping.cat.consumer.problem.model.entity.Segment;
import com.dianping.cat.consumer.problem.model.transform.DefaultXmlBuilder;
import com.dianping.cat.consumer.problem.model.transform.DefaultXmlParser;
import com.dianping.cat.message.spi.AbstractMessageAnalyzer;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.storage.Bucket;
import com.dianping.cat.storage.BucketManager;
import com.site.lookup.annotation.Inject;

public class ProblemAnalyzer extends AbstractMessageAnalyzer<ProblemReport> implements LogEnabled {
	private static final long MINUTE = 60 * 1000;

	@Inject
	private BucketManager m_bucketManager;

	@Inject
	private List<Handler> m_handlers;

	private Map<String, ProblemReport> m_reports = new HashMap<String, ProblemReport>();

	private long m_extraTime;

	private Logger m_logger;

	private long m_startTime;

	private long m_duration;

	void closeMessageBuckets(Set<String> set) {
		Date timestamp = new Date(m_startTime);

		for (String domain : m_reports.keySet()) {
			Bucket<MessageTree> localBucket = null;
			Bucket<MessageTree> remoteBucket = null;

			try {
				localBucket = m_bucketManager.getMessageBucket(new Date(m_startTime), domain, "local");
				remoteBucket = m_bucketManager.getMessageBucket(new Date(m_startTime), domain, "remote");
			} catch (Exception e) {
				m_logger.error(String.format("Error when getting message bucket of %s!", timestamp), e);
			} finally {
				if (localBucket != null) {
					m_bucketManager.closeBucket(localBucket);
				}

				if (remoteBucket != null) {
					m_bucketManager.closeBucket(remoteBucket);
				}
			}
		}
	}

	@Override
	public void doCheckpoint() throws IOException {
		storeReports(m_reports.values());
		closeMessageBuckets(m_reports.keySet());
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	private Segment findOrCreateSegment(ProblemReport report, MessageTree tree) {
		Machine machine = report.findOrCreateMachine(tree.getIpAddress());
		JavaThread thread = machine.findOrCreateThread(tree.getThreadId());
		Calendar cal = Calendar.getInstance();

		cal.setTimeInMillis(tree.getMessage().getTimestamp());

		int minute = cal.get(Calendar.MINUTE);
		Segment segment = thread.findOrCreateSegment(minute);
		return segment;
	}

	@Override
	protected List<ProblemReport> generate() {
		List<ProblemReport> reports = new ArrayList<ProblemReport>(m_reports.size());

		for (String domain : m_reports.keySet()) {
			ProblemReport report = generate(domain);

			reports.add(report);
		}

		return reports;
	}

	public ProblemReport generate(String domain) {
		if (domain == null) {
			List<String> domains = getDomains();

			domain = domains.size() > 0 ? domains.get(0) : null;
		}

		ProblemReport report = m_reports.get(domain);

		return report;
	}

	public List<String> getDomains() {
		List<String> domains = new ArrayList<String>(m_reports.keySet());

		Collections.sort(domains, new Comparator<String>() {
			@Override
			public int compare(String d1, String d2) {
				if (d1.equals("Cat")) {
					return 1;
				}

				return d1.compareTo(d2);
			}
		});

		return domains;
	}

	public ProblemReport getReport(String domain) {
		return m_reports.get(domain);
	}

	public Map<String, ProblemReport> getReports() {
		return m_reports;
	}

	@Override
	protected boolean isTimeout() {
		long currentTime = System.currentTimeMillis();
		long endTime = m_startTime + m_duration + m_extraTime;

		return currentTime > endTime;
	}

	void loadReports() {
		Date timestamp = new Date(m_startTime);
		DefaultXmlParser parser = new DefaultXmlParser();
		Bucket<String> bucket = null;

		try {
			bucket = m_bucketManager.getReportBucket(timestamp, "problem", "local");

			for (String id : bucket.getIdsByPrefix("")) {
				String xml = bucket.findById(id);
				ProblemReport report = parser.parse(xml);

				m_reports.put(report.getDomain(), report);
			}
		} catch (Exception e) {
			m_logger.error(String.format("Error when loading problem reports of %s!", timestamp), e);
		} finally {
			if (bucket != null) {
				m_bucketManager.closeBucket(bucket);
			}
		}
	}

	@Override
	protected void process(MessageTree tree) {
		String domain = tree.getDomain();
		ProblemReport report = m_reports.get(domain);

		if (report == null) {
			report = new ProblemReport(domain);
			report.setStartTime(new Date(m_startTime));
			report.setEndTime(new Date(m_startTime + MINUTE * 60 - 1));

			m_reports.put(domain, report);
		}

		Segment segment = findOrCreateSegment(report, tree);
		int count = 0;

		for (Handler handler : m_handlers) {
			count += handler.handle(segment, tree);
		}

		if (count > 0) {
			String messageId = tree.getMessageId();

			try {
				Bucket<MessageTree> localBucket = m_bucketManager.getMessageBucket(new Date(m_startTime), domain, "local");
				Bucket<MessageTree> remoteBucket = m_bucketManager
				      .getMessageBucket(new Date(m_startTime), domain, "remote");

				localBucket.storeById(messageId, tree);
				remoteBucket.storeById(messageId, tree);
			} catch (IOException e) {
				m_logger.error("Error when storing message for problem analyzer!", e);
			}
		}
	}

	public void setAnalyzerInfo(long startTime, long duration, long extraTime) {
		m_extraTime = extraTime;
		m_startTime = startTime;
		m_duration = duration;

		loadReports();
	}

	@Override
	protected void store(List<ProblemReport> reports) {
		if (reports == null || reports.size() == 0) {
			return;
		}

		storeReports(reports);
		closeMessageBuckets(m_reports.keySet());
	}

	void storeReports(Collection<ProblemReport> reports) {
		Date timestamp = new Date(m_startTime);
		DefaultXmlBuilder builder = new DefaultXmlBuilder(true);
		Bucket<String> localBucket = null;
		Bucket<String> remoteBucket = null;

		try {
			localBucket = m_bucketManager.getReportBucket(timestamp, "problem", "local");
			remoteBucket = m_bucketManager.getReportBucket(timestamp, "problem", "remote");

			// delete old one, not append mode
			localBucket.deleteAndCreate();

			for (ProblemReport report : reports) {
				String xml = builder.buildXml(report);
				String domain = report.getDomain();

				localBucket.storeById(domain, xml);
				remoteBucket.storeById(domain, xml);
			}
		} catch (Exception e) {
			m_logger.error(String.format("Error when storing problem reports to %s!", timestamp), e);
		} finally {
			if (localBucket != null) {
				m_bucketManager.closeBucket(localBucket);
			}

			if (remoteBucket != null) {
				m_bucketManager.closeBucket(remoteBucket);
			}
		}
	}
}
