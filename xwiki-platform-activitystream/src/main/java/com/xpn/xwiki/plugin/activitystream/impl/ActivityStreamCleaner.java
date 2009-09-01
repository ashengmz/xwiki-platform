/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xpn.xwiki.plugin.activitystream.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.plugin.activitystream.plugin.ActivityStreamPlugin;
import com.xpn.xwiki.plugin.scheduler.SchedulerPlugin;

/**
 * Manager for the activitystream cleaning feature. The cleaning consist in deleting old events to prevent infinite 
 * growth of the activitystream table in the database.
 * 
 * @version $Id$
 */
public class ActivityStreamCleaner
{
    /**
     * Logger.
     */
    private static final Log LOG = LogFactory.getLog(ActivityStreamCleaner.class);
    
    /**
     * Document holding the cleaner Job.
     */
    private static final String CLEANER_JOB_DOCNAME = "Scheduler.ActivityStreamCleaner";
    
    /**
     * Document holding the cleaner Job.
     */
    private static final String CLEANER_JOB_NAME = "ActivityStream cleaner";
    
    /**
     * Document holding the cleaner Job.
     */
    private static final String CLEANER_JOB_CRON = "0 0 0 ? * SUN";

    /**
     * XWiki Default Admin account.
     */
    private static final String XWIKI_DEFAULT_ADMIN = "XWiki.Admin";
    
    /**
     * XWiki Rights class name.
     */
    private static final String XWIKI_RIGHTS_CLASS = "XWiki.XWikiRights";
    
    /**
     * Unique instance of ActivityStreamCleaner.
     */
    private static ActivityStreamCleaner instance;

    /**
     * Hidden constructor of ActivityStreamCleaner only access via getInstance().
     */
    private ActivityStreamCleaner()
    {
    }

    /**
     * @return a unique instance of ActivityStreamCleaner. Thread safe.
     */
    public static ActivityStreamCleaner getInstance()
    {
        synchronized (ActivityStreamCleaner.class) {
            if (instance == null) {
                instance = new ActivityStreamCleaner();
            }
        }

        return instance;
    }
    
    /**
     * @param context the XWiki context
     * @return the number of days activitystream events should be kept (default: infinite duration). 
     */
    public static int getNumberOfDaysToKeep(XWikiContext context)
    {
        ActivityStreamPlugin plugin = 
            (ActivityStreamPlugin) context.getWiki().getPlugin(ActivityStreamPlugin.PLUGIN_NAME, context); 
        String pref = plugin.getActivityStreamPreference("daystokeepevents", "0", context);
        return Integer.parseInt(pref);
    }
                                              
    /**
     * Create the XWiki rights object in the cleaner job document.
     * 
     * @param doc Cleaner job document
     * @param context the XWiki context
     * @return true if the document has been updated, false otherwise
     * @throws XWikiException if the object creation fails
     */
    private boolean createWatchListJobRightsObject(XWikiDocument doc, XWikiContext context) throws XWikiException
    {
        BaseObject rights = doc.getObject(XWIKI_RIGHTS_CLASS);
        if (rights == null) {
            int index = doc.createNewObject(XWIKI_RIGHTS_CLASS, context);
            rights = doc.getObject(XWIKI_RIGHTS_CLASS, index);
            rights.setLargeStringValue("groups", "XWiki.XWikiAdminGroup");
            rights.setStringValue("levels", "edit,delete");
            rights.setIntValue("allow", 1);
            return true;
        }

        return false;
    }
    
    /**
     * Create the cleaner job document in the wiki.
     *     
     * @param context the XWiki context
     * @throws XWikiException if the job creation fails
     */
    private void initCleanerJob(XWikiContext context) throws XWikiException
    {
        XWikiDocument doc;
        boolean needsUpdate = false;
        BaseObject job = null;

        try {
            doc = context.getWiki().getDocument(CLEANER_JOB_DOCNAME, context);
            
            if (StringUtils.isBlank(doc.getAuthor())) {
                needsUpdate = true;
                doc.setAuthor(XWIKI_DEFAULT_ADMIN);
            }

            if (StringUtils.isBlank(doc.getCreator())) {
                needsUpdate = true;
                doc.setCreator(XWIKI_DEFAULT_ADMIN);
            }

            if (StringUtils.isBlank(doc.getParent())) {
                needsUpdate = true;
                doc.setParent("Scheduler.WebHome");
            }

            job = doc.getObject(SchedulerPlugin.XWIKI_JOB_CLASS);
            if (job == null) {
                needsUpdate = true;
                int index = doc.createNewObject(SchedulerPlugin.XWIKI_JOB_CLASS, context);
                job = doc.getObject(SchedulerPlugin.XWIKI_JOB_CLASS, index);
                job.setStringValue("jobName", CLEANER_JOB_NAME);
                job.setStringValue("jobClass", ActivityStreamCleanerJob.class.getName());
                job.setStringValue("cron", CLEANER_JOB_CRON);
                job.setStringValue("contextUser", XWIKI_DEFAULT_ADMIN);
                job.setStringValue("contextLang", "en");
                job.setStringValue("contextDatabase", "xwiki");
            }

            needsUpdate = createWatchListJobRightsObject(doc, context);

            if (StringUtils.isBlank(doc.getContent())) {
                needsUpdate = true;
                doc.setContent("{{include document=\"XWiki.SchedulerJobSheet\"/}}");
                doc.setSyntaxId(XWikiDocument.XWIKI20_SYNTAXID);
            }

            if (needsUpdate) {
                context.getWiki().saveDocument(doc, "", true, context);
                ((SchedulerPlugin) context.getWiki().getPlugin("scheduler", context)).scheduleJob(job, context);
            }
        } catch (Exception e) {
            LOG.error("Cannot initialize ActivityStreamCleanerJob", e);
        }
    }
    
    /**
     * Method that must be called on plugin init. Create the scheduler job.
     * 
     * @param context the XWiki context
     * @throws XWikiException if the job creation failed
     */
    public void init(XWikiContext context) throws XWikiException
    {
        if (getNumberOfDaysToKeep(context) > 0) {
            initCleanerJob(context);
        }
    }
}
