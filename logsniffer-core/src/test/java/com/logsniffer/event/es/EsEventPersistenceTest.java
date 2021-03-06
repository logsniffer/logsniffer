/*******************************************************************************
 * logsniffer, open source tool for viewing, monitoring and analysing log data.
 * Copyright (c) 2015 Scaleborn UG, www.scaleborn.com
 *
 * logsniffer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * logsniffer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package com.logsniffer.event.es;

import java.util.ArrayList;
import java.util.Date;

import org.elasticsearch.client.Client;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logsniffer.app.CoreAppConfig;
import com.logsniffer.app.ElasticSearchAppConfig;
import com.logsniffer.app.ElasticSearchAppConfig.ClientCallback;
import com.logsniffer.app.ElasticSearchAppConfig.ElasticClientTemplate;
import com.logsniffer.app.QaDataSourceAppConfig;
import com.logsniffer.event.Event;
import com.logsniffer.event.Sniffer;
import com.logsniffer.event.SnifferPersistence;
import com.logsniffer.event.es.EsEventPersistenceTest.HelperAppConfig;
import com.logsniffer.model.LogEntry;
import com.logsniffer.model.LogSource;
import com.logsniffer.model.LogSourceProvider;
import com.logsniffer.model.file.WildcardLogsSource;
import com.logsniffer.model.support.DefaultPointer;

/**
 * Test for {@link EsEventPersistence}.
 * 
 * @author mbok
 * 
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = { HelperAppConfig.class, CoreAppConfig.class, QaDataSourceAppConfig.class,
		ElasticSearchAppConfig.class })
public class EsEventPersistenceTest {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Configuration
	public static class HelperAppConfig {
		@Bean
		public EsEventPersistence eventPersister() {
			return new EsEventPersistence();
		}

		@Bean
		public ConversionService conversionService() {
			return new DefaultFormattingConversionService();
		}

		@Bean
		public LogSourceProvider sourceProvider() {
			return Mockito.mock(LogSourceProvider.class);
		}

		@Bean
		public SnifferPersistence snifferPersistence() {
			return Mockito.mock(SnifferPersistence.class);
		}

		@Bean
		public IndexNamingStrategy nameStrategy() {
			return new IndexNamingStrategy() {

				@Override
				public String buildActiveName(final long snifferId) {
					return "test";
				}

				@Override
				public String[] getRetrievalNames(final long snifferId) {
					return new String[] { buildActiveName(snifferId), "temp" };
				}
			};
		}
	}

	@Autowired
	private ElasticClientTemplate clientTpl;

	@Autowired
	private EsEventPersistence persister;

	@Autowired
	private LogSourceProvider sourceProvider;

	@Autowired
	private SnifferPersistence snifferPersistence;

	@Autowired
	private ObjectMapper objectMapper;

	private final WildcardLogsSource source1 = new WildcardLogsSource();

	private final Sniffer sniffer1 = new Sniffer();

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	public void testPersist() throws Exception {
		// Assert.assertEquals(0,
		// persister.getEventsQueryBuilder(sniffer1.getId(), 0, 10).list()
		// .size());
		final Event e = new Event();
		e.setLogPath("log");
		e.setSnifferId(sniffer1.getId());
		e.setLogSourceId(source1.getId());
		final LogEntry entry1 = new LogEntry();
		entry1.setRawContent("1");
		entry1.setStartOffset(new DefaultPointer(0, 1));
		entry1.setEndOffset(new DefaultPointer(1, 1));
		entry1.put("f1", new Date(0));
		final LogEntry entry2 = new LogEntry();
		entry2.setStartOffset(new DefaultPointer(1, 2));
		entry2.setEndOffset(new DefaultPointer(2, 2));
		entry2.setRawContent("2");
		final ArrayList<LogEntry> entries = new ArrayList<LogEntry>();
		entries.add(entry1);
		entries.add(entry2);
		e.setEntries(entries);
		e.setPublished(new Date(1000 * 100));
		e.put("my", "value");
		final String eventId = persister.persist(e);

		logger.info("Serialized event as: {}", objectMapper.writeValueAsString(e));

		clientTpl.executeWithClient(new ClientCallback<Object>() {
			@Override
			public Object execute(final Client client) {
				client.admin().indices().prepareRefresh().get();
				return null;
			}
		});
		// Check
		Assert.assertEquals(1, persister.getEventsQueryBuilder(sniffer1.getId(), 0, 10).list().getItems().size());
		Assert.assertEquals(1, persister.getEventsQueryBuilder(sniffer1.getId(), 0, 10).list().getTotalCount());
		final Event checkEvent = persister.getEvent(sniffer1.getId(), eventId);
		Assert.assertEquals(sniffer1.getId(), checkEvent.getSnifferId());
		Assert.assertEquals(source1.getId(), checkEvent.getLogSourceId());
		Assert.assertEquals("log", checkEvent.getLogPath());
		Assert.assertEquals(1000 * 100, checkEvent.getPublished().getTime());
		Assert.assertEquals(2, checkEvent.getEntries().size());
		Assert.assertEquals("1", checkEvent.getEntries().get(0).getRawContent());
		Assert.assertEquals(new Date(0), checkEvent.getEntries().get(0).get("f1"));
		Assert.assertEquals(entry1.getStartOffset().getJson(),
				checkEvent.getEntries().get(0).getStartOffset().getJson());
		Assert.assertEquals("2", checkEvent.getEntries().get(1).getRawContent());
		Assert.assertEquals("value", checkEvent.get("my"));

		// Check offset
		Assert.assertEquals(0, persister.getEventsQueryBuilder(sniffer1.getId(), 1, 10).list().getItems().size());
		Assert.assertEquals(1, persister.getEventsQueryBuilder(sniffer1.getId(), 1, 10).list().getTotalCount());

		// Delete event
		Assert.assertNotNull(persister.getEvent(sniffer1.getId(), eventId));
		persister.delete(sniffer1.getId(), new String[] { eventId });
		Assert.assertNull(persister.getEvent(sniffer1.getId(), eventId));

		// Delete all events
		Mockito.when(snifferPersistence.getSniffer(sniffer1.getId())).thenReturn(sniffer1);
		Mockito.when(sourceProvider.getSourceById(sniffer1.getLogSourceId())).thenReturn((LogSource) source1);
		persister.deleteAll(sniffer1.getId());
		persister.deleteAll(sniffer1.getId());
	}

}
