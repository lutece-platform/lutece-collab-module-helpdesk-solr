/*
 * Copyright (c) 2002-2008, Mairie de Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.helpdesk.modules.solr.search;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.demo.html.HTMLParser;
import fr.paris.lutece.plugins.helpdesk.business.Faq;
import fr.paris.lutece.plugins.helpdesk.business.FaqHome;
import fr.paris.lutece.plugins.helpdesk.business.QuestionAnswer;
import fr.paris.lutece.plugins.helpdesk.business.Subject;
import fr.paris.lutece.plugins.helpdesk.business.SubjectHome;
import fr.paris.lutece.plugins.helpdesk.service.HelpdeskPlugin;
import fr.paris.lutece.plugins.helpdesk.web.HelpdeskApp;
import fr.paris.lutece.plugins.search.solr.business.field.Field;
import fr.paris.lutece.plugins.search.solr.indexer.SolrIndexer;
import fr.paris.lutece.plugins.search.solr.indexer.SolrItem;
import fr.paris.lutece.portal.service.content.XPageAppService;
import fr.paris.lutece.portal.service.plugin.Plugin;
import fr.paris.lutece.portal.service.plugin.PluginService;
import fr.paris.lutece.portal.service.util.AppLogService;
import fr.paris.lutece.portal.service.util.AppPathService;
import fr.paris.lutece.portal.service.util.AppPropertiesService;
import fr.paris.lutece.util.url.UrlItem;


/**
 * The Helpdesk indexer for Solr search platform
 *
 */
public class SolrHelpdeskIndexer implements SolrIndexer
{
    private static final String PROPERTY_DESCRIPTION = "helpdesk-solr.indexer.description";
    private static final String PROPERTY_NAME = "helpdesk-solr.indexer.name";
    private static final String PROPERTY_VERSION = "helpdesk-solr.indexer.version";
    private static final String PROPERTY_INDEXER_ENABLE = "helpdesk-solr.indexer.enable";
    public static final String SHORT_NAME_SUBJECT = "hds";
    public static final String SHORT_NAME_QUESTION_ANSWER = "hdq";
    private static final String SITE = AppPropertiesService.getProperty( "lutece.name" );
    private static final String BLANK = " ";
    private static final String PROPERTY_PAGE_PATH_LABEL = "helpdesk.pagePathLabel";

    public String getDescription(  )
    {
        return AppPropertiesService.getProperty( PROPERTY_DESCRIPTION );
    }

    public String getName(  )
    {
        return AppPropertiesService.getProperty( PROPERTY_NAME );
    }

    public String getVersion(  )
    {
        return AppPropertiesService.getProperty( PROPERTY_VERSION );
    }

    public Map<String, SolrItem> index(  )
    {
        Map<String, SolrItem> items = new HashMap<String, SolrItem>(  );
        Plugin plugin = PluginService.getPlugin( HelpdeskPlugin.PLUGIN_NAME );

        //FAQ
        for ( Faq faq : FaqHome.findAll( plugin ) )
        {
            for ( Subject subject : (Collection<Subject>) SubjectHome.getInstance(  ).findByIdFaq( faq.getId(  ), plugin ) )
            {
                try
                {
                    indexSubject( items, faq, subject );
                }
                catch ( IOException e )
                {
                    AppLogService.error( e );
                }
            }
        }

        return items;
    }

    /**
     * Recursive method for indexing a subject and his children
     *
     * @param items The items map
     * @param faq the faq linked to the subject
     * @param subject the subject
     * @throws IOException I/O Exception
     * @throws InterruptedException interruptedException
     */
    private void indexSubject( Map<String, SolrItem> items, Faq faq, Subject subject )
        throws IOException
    {
        String strPortalUrl = AppPathService.getPortalUrl(  );
        Plugin plugin = PluginService.getPlugin( HelpdeskPlugin.PLUGIN_NAME );

        UrlItem urlSubject = new UrlItem( strPortalUrl );
        urlSubject.addParameter( XPageAppService.PARAM_XPAGE_APP,
            AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) ); //FIXME
        urlSubject.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, faq.getId(  ) );
        urlSubject.setAnchor( HelpdeskApp.ANCHOR_SUBJECT + subject.getId(  ) );

        // Indexing the subject
        SolrItem itemSubject = getDocument( subject, faq.getRoleKey(  ), urlSubject.getUrl(  ), plugin );
        items.put( getLog( itemSubject ), itemSubject );

        for ( QuestionAnswer questionAnswer : (List<QuestionAnswer>) subject.getQuestions(  ) )
        {
            if ( questionAnswer.isEnabled(  ) )
            {
                UrlItem urlQuestionAnswer = new UrlItem( strPortalUrl );
                urlQuestionAnswer.addParameter( XPageAppService.PARAM_XPAGE_APP,
                    AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) ); //FIXME
                urlQuestionAnswer.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, faq.getId(  ) );
                urlQuestionAnswer.setAnchor( HelpdeskApp.ANCHOR_QUESTION_ANSWER +
                    questionAnswer.getIdQuestionAnswer(  ) );

                // Indexing the questionAnswer
                SolrItem itemQuestionAnswer = getDocument( faq.getId(  ), questionAnswer, urlQuestionAnswer.getUrl(  ),
                        faq.getRoleKey(  ), plugin );
                items.put( getLog( itemQuestionAnswer ), itemQuestionAnswer );
            }
        }

        Collection<Subject> children = subject.getChilds( plugin );

        for ( Subject childSubject : children )
        {
            indexSubject( items, faq, childSubject );
        }
    }

    /**
     * Builds a {@link SolrItem} which will be used by Solr during the indexing of the question/answer list
     *
     * @param nIdFaq The {@link Faq} Id
     * @param questionAnswer the {@link QuestionAnswer} to index
     * @param strUrl the url of the subject
     * @param strRoleKey The role key
     * @param plugin The {@link Plugin}
     * @return A Solr {@link SolrItem} containing QuestionAnswer Data
     * @throws IOException The IO Exception
     */
    private SolrItem getDocument( int nIdFaq, QuestionAnswer questionAnswer, String strUrl, String strRoleKey,
        Plugin plugin ) throws IOException
    {
        // make a new, empty document
        SolrItem item = new SolrItem(  );

        //FIXME
        //        doc.add( new Field( HelpdeskSearchItem.FIELD_FAQ_ID, String.valueOf( nIdFaq ), Field.Store.YES,
        //                Field.Index.UN_TOKENIZED ) );

        // Setting the Role field
        item.setRole( strRoleKey );

        // Setting the URL field
        item.setUrl( strUrl );

        //FIXME
        //        doc.add( new Field( HelpdeskSearchItem.FIELD_SUBJECT, String.valueOf( questionAnswer.getIdSubject(  ) ),
        //                Field.Store.YES, Field.Index.UN_TOKENIZED ) );

        // Setting the Uid field
        String strIdQuestionAnswer = String.valueOf( questionAnswer.getIdQuestionAnswer(  ) );
        item.setUid( strIdQuestionAnswer + "_" + SHORT_NAME_QUESTION_ANSWER );

        // Setting the Date field
        // Add the last modified date of the file a field named "modified".
        item.setDate( questionAnswer.getCreationDate(  ) );

        //Setting the Content field
        String strContentToIndex = getContentToIndex( questionAnswer, plugin );
        StringReader readerPage = new StringReader( strContentToIndex );
        HTMLParser parser = new HTMLParser( readerPage );

        Reader reader = parser.getReader(  );
        int c;
        StringBuffer sb = new StringBuffer(  );

        while ( ( c = reader.read(  ) ) != -1 )
        {
            sb.append( String.valueOf( (char) c ) );
        }

        reader.close(  );
        item.setContent( sb.toString(  ) );

        // Setting the Title field
        item.setTitle( questionAnswer.getQuestion(  ) );

        // Setting the Site field
        item.setSite( SITE );

        // Setting the Type field
        item.setType( HelpdeskPlugin.PLUGIN_NAME );

        // return the document
        return item;
    }

    /**
     * Builds a {@link SolrItem} element which will be used by Solr during the indexing of the subject list
     *
     * @param subject the {@link Subject} to index
     * @param strUrl the url of the subject
     * @param strRoleKey The role key
     * @param plugin The {@link Plugin}
     * @return The Solr {@link SolrItem} containing Subject data
     * @throws IOException The IO Exception
     */
    private SolrItem getDocument( Subject subject, String strRoleKey, String strUrl, Plugin plugin )
        throws IOException
    {
        // make a new, empty document
        SolrItem item = new SolrItem(  );

        // Setting the URL field
        item.setUrl( strUrl );

        // Setting the Uid field
        String strIdSubject = String.valueOf( subject.getId(  ) );
        item.setUid( strIdSubject + "_" + SHORT_NAME_SUBJECT );

        //Setting the Content field
        String strContentToIndex = subject.getText(  );
        StringReader readerPage = new StringReader( strContentToIndex );
        HTMLParser parser = new HTMLParser( readerPage );

        Reader reader = parser.getReader(  );
        int c;
        StringBuffer sb = new StringBuffer(  );

        while ( ( c = reader.read(  ) ) != -1 )
        {
            sb.append( String.valueOf( (char) c ) );
        }

        reader.close(  );
        item.setContent( sb.toString(  ) );

        // Setting the Title field
        item.setTitle( subject.getText(  ) );

        // Setting the Site field
        item.setSite( SITE );

        // Setting the Type field
        item.setType( HelpdeskPlugin.PLUGIN_NAME );

        // Setting the Role field
        item.setRole( strRoleKey );

        // return the document
        return item;
    }

    /**
     * Set the Content to index (Question and Answer)
     * @param questionAnswer The {@link QuestionAnswer} to index
     * @param plugin The {@link Plugin}
     * @return The content to index
     */
    private static String getContentToIndex( QuestionAnswer questionAnswer, Plugin plugin )
    {
        StringBuffer sbContentToIndex = new StringBuffer(  );
        //Do not index question here
        sbContentToIndex.append( questionAnswer.getQuestion(  ) );
        sbContentToIndex.append( BLANK );
        sbContentToIndex.append( questionAnswer.getAnswer(  ) );

        return sbContentToIndex.toString(  );
    }

    public boolean isEnable(  )
    {
        return "true".equalsIgnoreCase( AppPropertiesService.getProperty( PROPERTY_INDEXER_ENABLE ) );
    }

    public List<Field> getAdditionalFields(  )
    {
        return new ArrayList<Field>(  );
    }

    /**
     * Generate the log line for the specified {@link SolrItem}
     * @param item The {@link SolrItem}
     * @return The string representing the log line
     */
    private String getLog( SolrItem item )
    {
        StringBuilder sbLogs = new StringBuilder(  );
        sbLogs.append( "indexing " );
        sbLogs.append( item.getType(  ) );
        sbLogs.append( " id : " );
        sbLogs.append( item.getUid(  ) );
        sbLogs.append( " Title : " );
        sbLogs.append( item.getTitle(  ) );
        sbLogs.append( "<br/>" );

        return sbLogs.toString(  );
    }
}
