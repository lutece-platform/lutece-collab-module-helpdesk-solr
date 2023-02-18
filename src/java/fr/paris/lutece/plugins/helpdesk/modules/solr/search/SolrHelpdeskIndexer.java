/*
 * Copyright (c) 2002-2022, City of Paris
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import fr.paris.lutece.plugins.helpdesk.business.Faq;
import fr.paris.lutece.plugins.helpdesk.business.FaqHome;
import fr.paris.lutece.plugins.helpdesk.business.QuestionAnswer;
import fr.paris.lutece.plugins.helpdesk.business.Subject;
import fr.paris.lutece.plugins.helpdesk.business.SubjectHome;
import fr.paris.lutece.plugins.helpdesk.service.HelpdeskPlugin;
import fr.paris.lutece.plugins.helpdesk.service.helpdesksearch.HelpdeskSearchItem;
import fr.paris.lutece.plugins.helpdesk.utils.HelpdeskIndexerUtils;
import fr.paris.lutece.plugins.helpdesk.web.HelpdeskApp;
import fr.paris.lutece.plugins.search.solr.business.field.Field;
import fr.paris.lutece.plugins.search.solr.indexer.SolrIndexer;
import fr.paris.lutece.plugins.search.solr.indexer.SolrIndexerService;
import fr.paris.lutece.plugins.search.solr.indexer.SolrItem;
import fr.paris.lutece.plugins.search.solr.util.SolrConstants;
import fr.paris.lutece.portal.service.content.XPageAppService;
import fr.paris.lutece.portal.service.plugin.Plugin;
import fr.paris.lutece.portal.service.plugin.PluginService;
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
    private static final String BLANK = " ";
    private static final String PROPERTY_PAGE_PATH_LABEL = "helpdesk.pagePathLabel";
    private static final String PROPERTY_FAQ_ID_LABEL = "helpdesk-solr.indexer.faq_id.label";
    private static final String PROPERTY_FAQ_ID_DESCRIPTION = "helpdesk-solr.indexer.faq_id.description";
    private static final String PROPERTY_SUBJECT_LABEL = "helpdesk-solr.indexer.subject.label";
    private static final String PROPERTY_SUBJECT_DESCRIPTION = "helpdesk-solr.indexer.subject.description";

    private static final String PROPERTY_BATCH_SIZE = "helpdesk-solr.index.writer.commit.size";

    // Site name
    private static final List<String> LIST_RESSOURCES_NAME = new ArrayList<String>( );
    
    public SolrHelpdeskIndexer( )
    {
        super( );
        LIST_RESSOURCES_NAME.add( HelpdeskIndexerUtils.CONSTANT_QUESTION_ANSWER_TYPE_RESOURCE );
        LIST_RESSOURCES_NAME.add( HelpdeskIndexerUtils.CONSTANT_SUBJECT_TYPE_RESOURCE );
    }

    /**
     * {@inheritDoc}
     */
    public String getDescription( )
    {
        return AppPropertiesService.getProperty( PROPERTY_DESCRIPTION );
    }

    /**
     * {@inheritDoc}
     */
    public String getName( )
    {
        return AppPropertiesService.getProperty( PROPERTY_NAME );
    }

    /**
     * {@inheritDoc}
     */
    public String getVersion( )
    {
        return AppPropertiesService.getProperty( PROPERTY_VERSION );
    }

    /**
     * {@inheritDoc}
     */
    public List<String> indexDocuments( )
    {
        Plugin plugin = PluginService.getPlugin( HelpdeskPlugin.PLUGIN_NAME );
        List<String> listErrors = new ArrayList<String>( );

        Collection<Subject> collectionSubject = (Collection<Subject>) SubjectHome.getInstance( ).findAll( plugin );

        try
        {
            for ( Collection<Subject> subjectsBatch : splitInBatchs( collectionSubject, AppPropertiesService.getPropertyInt( PROPERTY_BATCH_SIZE, 100 ) ) )
            {
                SolrIndexerService.write( getSolrItems( subjectsBatch ) );
            }
        }
        catch( IOException e )
        {
            listErrors.add( SolrIndexerService.buildErrorMessage( e ) );
        }

        return listErrors;
    }

    public static Collection<List<Subject>> splitInBatchs( Collection<Subject> collectionSubject, int batchSize )
    {
        AtomicInteger counter = new AtomicInteger( );
        return collectionSubject.stream( ).collect( Collectors.groupingBy( it -> counter.getAndIncrement( ) / batchSize ) ).values( );
    }

    private Collection<SolrItem> getSolrItems( Collection<Subject> collectionSubject ) throws IOException
    {
        Plugin plugin = PluginService.getPlugin( HelpdeskPlugin.PLUGIN_NAME );
        Collection<SolrItem> collectionSolrItem = new ArrayList<>( );
        String strWebappName = SolrIndexerService.getBaseUrl( );

        for ( Subject subject : collectionSubject )
        {
            UrlItem urlSubject = new UrlItem( strWebappName );
            urlSubject.addParameter( XPageAppService.PARAM_XPAGE_APP, AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) );
            urlSubject.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, subject.getFaq( plugin ).getId( ) );
            urlSubject.setAnchor( HelpdeskApp.ANCHOR_SUBJECT + subject.getId( ) );
            SolrItem item = getDocument( subject, subject.getFaq( plugin ).getRoleKey( ), urlSubject.getUrl( ), plugin );

            for ( Subject children : subject.getChilds( plugin ) )
            {
                UrlItem urlChildren = new UrlItem( strWebappName );
                urlChildren.addParameter( XPageAppService.PARAM_XPAGE_APP, AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) );
                urlChildren.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, children.getFaq( plugin ).getId( ) );
                urlChildren.setAnchor( HelpdeskApp.ANCHOR_SUBJECT + children.getId( ) );
                String strParent = HelpdeskSearchItem.FIELD_SUBJECT + "_" + subject.getId( );
                SolrItem sorlChildren = getDocument( children, children.getFaq( plugin ).getRoleKey( ), urlChildren.getUrl( ), plugin );
                item.addChildDocument( strParent, sorlChildren );
            }

            collectionSolrItem.add( item );

            for ( QuestionAnswer questionAnswer : (List<QuestionAnswer>) subject.getQuestions( ) )
            {
                if ( questionAnswer.isEnabled( ) )
                {
                    UrlItem urlQuestionAnswer = new UrlItem( strWebappName );
                    urlQuestionAnswer.addParameter( XPageAppService.PARAM_XPAGE_APP, AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) );
                    urlQuestionAnswer.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, subject.getFaq( plugin ).getId( ) );
                    urlQuestionAnswer.setAnchor( HelpdeskApp.ANCHOR_QUESTION_ANSWER + questionAnswer.getIdQuestionAnswer( ) );

                    // Indexing the questionAnswer
                    collectionSolrItem.add( getDocument( subject.getFaq( plugin ).getId( ), questionAnswer, urlQuestionAnswer.getUrl( ),
                            subject.getFaq( plugin ).getRoleKey( ), plugin ) );
                }
            }
        }

        return collectionSolrItem;
    }

    /**
     * Get the subject document
     * 
     * @param strDocument
     *            id of the subject to index
     * @return The list of Solr items
     */
    public List<SolrItem> getDocuments( String strDocument )
    {
        List<SolrItem> listDocs = new ArrayList<SolrItem>( );
        String strPortalUrl = SolrIndexerService.getBaseUrl( );
        Plugin plugin = PluginService.getPlugin( HelpdeskPlugin.PLUGIN_NAME );

        Subject subject = (Subject) SubjectHome.getInstance( ).findByPrimaryKey( Integer.parseInt( strDocument ), plugin );

        if ( subject != null )
        {
            UrlItem urlSubject = new UrlItem( strPortalUrl );
            urlSubject.addParameter( XPageAppService.PARAM_XPAGE_APP, AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) );

            // if it's a sub-subject, we need to get the first parent to have the faq
            int nIdParent = subject.getIdParent( );
            Subject parentSubject = subject;

            while ( nIdParent != SubjectHome.FIRST_ORDER )
            {
                parentSubject = (Subject) SubjectHome.getInstance( ).findByPrimaryKey( nIdParent, plugin );
                nIdParent = parentSubject.getIdParent( );
            }

            Faq faq = FaqHome.findBySubjectId( parentSubject.getId( ), plugin );

            if ( faq != null )
            {
                urlSubject.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, faq.getId( ) );
                urlSubject.setAnchor( HelpdeskApp.ANCHOR_SUBJECT + subject.getId( ) );

                SolrItem docSubject;

                try
                {
                    docSubject = getDocument( subject, faq.getRoleKey( ), urlSubject.getUrl( ), plugin );

                    listDocs.add( docSubject );

                    for ( QuestionAnswer questionAnswer : (List<QuestionAnswer>) subject.getQuestions( ) )
                    {
                        if ( questionAnswer.isEnabled( ) )
                        {
                            UrlItem urlQuestionAnswer = new UrlItem( strPortalUrl );
                            urlQuestionAnswer.addParameter( XPageAppService.PARAM_XPAGE_APP, AppPropertiesService.getProperty( PROPERTY_PAGE_PATH_LABEL ) );
                            urlQuestionAnswer.addParameter( HelpdeskApp.PARAMETER_FAQ_ID, faq.getId( ) );
                            urlQuestionAnswer.setAnchor( HelpdeskApp.ANCHOR_QUESTION_ANSWER + questionAnswer.getIdQuestionAnswer( ) );

                            SolrItem docQuestionAnswer = getDocument( faq.getId( ), questionAnswer, urlQuestionAnswer.getUrl( ), faq.getRoleKey( ), plugin );
                            listDocs.add( docQuestionAnswer );
                        }
                    }
                }
                catch( IOException e )
                {
                    throw new RuntimeException( e );
                }
            }
        }

        return listDocs;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEnable( )
    {
        return "true".equalsIgnoreCase( AppPropertiesService.getProperty( PROPERTY_INDEXER_ENABLE ) );
    }

    /**
     * {@inheritDoc}
     */
    public List<Field> getAdditionalFields( )
    {
        List<Field> fields = new ArrayList<Field>( );
        Field fieldFaqId = new Field( );
        fieldFaqId.setEnableFacet( false );
        fieldFaqId.setName( HelpdeskSearchItem.FIELD_FAQ_ID );
        fieldFaqId.setLabel( AppPropertiesService.getProperty( PROPERTY_FAQ_ID_LABEL ) );
        fieldFaqId.setDescription( AppPropertiesService.getProperty( PROPERTY_FAQ_ID_DESCRIPTION ) );
        fields.add( fieldFaqId );

        Field fieldSubjectId = new Field( );
        fieldSubjectId.setEnableFacet( true );
        fieldSubjectId.setName( HelpdeskSearchItem.FIELD_SUBJECT );
        fieldSubjectId.setLabel( AppPropertiesService.getProperty( PROPERTY_SUBJECT_LABEL ) );
        fieldSubjectId.setDescription( AppPropertiesService.getProperty( PROPERTY_SUBJECT_DESCRIPTION ) );
        fields.add( fieldSubjectId );

        return fields;
    }

    /**
     * Builds a {@link SolrItem} which will be used by Solr during the indexing of the question/answer list
     *
     * @param nIdFaq
     *            The {@link Faq} Id
     * @param questionAnswer
     *            the {@link QuestionAnswer} to index
     * @param strUrl
     *            the url of the subject
     * @param strRoleKey
     *            The role key
     * @param plugin
     *            The {@link Plugin}
     * @return A Solr {@link SolrItem} containing QuestionAnswer Data
     * @throws IOException
     *             The IO Exception
     */
    private SolrItem getDocument( int nIdFaq, QuestionAnswer questionAnswer, String strUrl, String strRoleKey, Plugin plugin ) throws IOException
    {
        // make a new, empty document
        SolrItem item = new SolrItem( );

        // Setting the Id faq field
        item.addDynamicField( HelpdeskSearchItem.FIELD_FAQ_ID, String.valueOf( nIdFaq ) );

        // Setting the Role field
        item.setRole( strRoleKey );

        // Setting the URL field
        item.setUrl( strUrl );

        // Setting the subject field
        item.addDynamicField( HelpdeskSearchItem.FIELD_SUBJECT, String.valueOf( questionAnswer.getIdSubject( ) ) );

        // Setting the Uid field
        String strIdQuestionAnswer = String.valueOf( questionAnswer.getIdQuestionAnswer( ) );
        item.setUid( getResourceUid( strIdQuestionAnswer, HelpdeskIndexerUtils.CONSTANT_QUESTION_ANSWER_TYPE_RESOURCE ) );

        // Setting the Date field
        // Add the last modified date of the file a field named "modified".
        item.setDate( questionAnswer.getCreationDate( ) );

        // Setting the Content field
        String strContentToIndex = getContentToIndex( questionAnswer, plugin );

        HtmlParser parser = new HtmlParser( );
        ContentHandler handler = new BodyContentHandler( );
        Metadata metadata = new Metadata( );
        InputStream stream = new ByteArrayInputStream( strContentToIndex.getBytes( StandardCharsets.UTF_8 ) );
        try
        {
            parser.parse( stream, handler, metadata, new ParseContext( ) );
        }
        catch( SAXException e )
        {
            e.printStackTrace( );
        }
        catch( TikaException e )
        {
            e.printStackTrace( );
        }

        item.setContent( handler.toString( ) );

        // Setting the Title field
        item.setTitle( questionAnswer.getQuestion( ) );

        // Setting the Site field
        item.setSite( SolrIndexerService.getWebAppName( ) );

        // Setting the Type field
        item.setType( HelpdeskPlugin.PLUGIN_NAME );

        // return the document
        return item;
    }

    /**
     * Builds a {@link SolrItem} element which will be used by Solr during the indexing of the subject list
     *
     * @param subject
     *            the {@link Subject} to index
     * @param strUrl
     *            the url of the subject
     * @param strRoleKey
     *            The role key
     * @param plugin
     *            The {@link Plugin}
     * @return The Solr {@link SolrItem} containing Subject data
     * @throws IOException
     *             The IO Exception
     */
    private SolrItem getDocument( Subject subject, String strRoleKey, String strUrl, Plugin plugin ) throws IOException
    {
        // make a new, empty document
        SolrItem item = new SolrItem( );

        // Setting the URL field
        item.setUrl( strUrl );

        // Setting the Uid field
        String strIdSubject = String.valueOf( subject.getId( ) );
        item.setUid( getResourceUid( strIdSubject, HelpdeskIndexerUtils.CONSTANT_SUBJECT_TYPE_RESOURCE ) );

        // Setting the Content field
        String strContentToIndex = subject.getText( );

        HtmlParser parser = new HtmlParser( );
        ContentHandler handler = new BodyContentHandler( );
        Metadata metadata = new Metadata( );
        InputStream stream = new ByteArrayInputStream( strContentToIndex.getBytes( StandardCharsets.UTF_8 ) );
        try
        {
            parser.parse( stream, handler, metadata, new ParseContext( ) );
        }
        catch( SAXException e )
        {
            e.printStackTrace( );
        }
        catch( TikaException e )
        {
            e.printStackTrace( );
        }

        item.setContent( strContentToIndex.toString( ) );

        // Setting the Title field
        item.setTitle( subject.getText( ) );

        // Setting the Site field
        item.setSite( SolrIndexerService.getWebAppName( ) );

        // Setting the Type field
        item.setType( HelpdeskPlugin.PLUGIN_NAME );

        // Setting the Role field
        item.setRole( strRoleKey );

        // return the document
        return item;
    }

    /**
     * Set the Content to index (Question and Answer)
     * 
     * @param questionAnswer
     *            The {@link QuestionAnswer} to index
     * @param plugin
     *            The {@link Plugin}
     * @return The content to index
     */
    private static String getContentToIndex( QuestionAnswer questionAnswer, Plugin plugin )
    {
        StringBuffer sbContentToIndex = new StringBuffer( );
        // Do not index question here
        sbContentToIndex.append( questionAnswer.getQuestion( ) );
        sbContentToIndex.append( BLANK );
        sbContentToIndex.append( questionAnswer.getAnswer( ) );

        return sbContentToIndex.toString( );
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getResourcesName( )
    {
        return LIST_RESSOURCES_NAME;
    }

    /**
     * {@inheritDoc}
     */
    public String getResourceUid( String strResourceId, String strResourceType )
    {
        StringBuffer sb = new StringBuffer( );

        if ( HelpdeskIndexerUtils.CONSTANT_QUESTION_ANSWER_TYPE_RESOURCE.equals( strResourceType ) )
        {
            sb.append( strResourceId ).append( SolrConstants.CONSTANT_UNDERSCORE ).append( SHORT_NAME_QUESTION_ANSWER );
        }
        else
            if ( HelpdeskIndexerUtils.CONSTANT_SUBJECT_TYPE_RESOURCE.equals( strResourceType ) )
            {
                sb.append( strResourceId ).append( SolrConstants.CONSTANT_UNDERSCORE ).append( SHORT_NAME_SUBJECT );
            }

        return StringUtils.isNotBlank( sb.toString( ) ) ? sb.toString( ) : null;
    }
}
