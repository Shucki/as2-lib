/**
 * The FreeBSD Copyright
 * Copyright 1994-2008 The FreeBSD Project. All rights reserved.
 * Copyright (C) 2013-2019 Philip Helger philip[at]helger[dot]com
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE FREEBSD PROJECT ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE FREEBSD PROJECT OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation
 * are those of the authors and should not be interpreted as representing
 * official policies, either expressed or implied, of the FreeBSD Project.
 */
package com.helger.as2lib.processor.receiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.as2lib.exception.OpenAS2Exception;
import com.helger.as2lib.exception.WrappedOpenAS2Exception;
import com.helger.as2lib.message.IMessage;
import com.helger.as2lib.params.InvalidParameterException;
import com.helger.as2lib.params.MessageParameters;
import com.helger.as2lib.processor.CFileAttribute;
import com.helger.as2lib.processor.sender.IProcessorSenderModule;
import com.helger.as2lib.session.IAS2Session;
import com.helger.as2lib.util.AS2IOHelper;
import com.helger.commons.annotation.ReturnsMutableObject;
import com.helger.commons.collection.attr.IStringMap;
import com.helger.commons.collection.impl.CommonsHashMap;
import com.helger.commons.collection.impl.ICommonsMap;
import com.helger.commons.http.CHttpHeader;
import com.helger.commons.io.file.FileIOError;
import com.helger.commons.io.file.SimpleFileIO;
import com.helger.commons.io.stream.StreamHelper;
import com.helger.commons.mime.CMimeType;
import com.helger.mail.cte.EContentTransferEncoding;
import com.helger.mail.datasource.ByteArrayDataSource;

public abstract class AbstractDirectoryPollingModule extends AbstractActivePollingModule
{
  public static final String ATTR_OUTBOX_DIRECTORY = "outboxdir";
  public static final String ATTR_ERROR_DIRECTORY = "errordir";
  public static final String ATTR_SENT_DIRECTORY = "sentdir";
  public static final String ATTR_FORMAT = "format";
  public static final String ATTR_DELIMITERS = "delimiters";
  public static final String ATTR_DEFAULTS = "defaults";
  public static final String ATTR_MIMETYPE = "mimetype";
  public static final String ATTR_SENDFILENAME = "sendfilename";

  private static final Logger LOGGER = LoggerFactory.getLogger (AbstractDirectoryPollingModule.class);

  private ICommonsMap <String, Long> m_aTrackedFiles;

  @Override
  public void initDynamicComponent (@Nonnull final IAS2Session aSession,
                                    @Nullable final IStringMap aOptions) throws OpenAS2Exception
  {
    super.initDynamicComponent (aSession, aOptions);
    getAttributeAsStringRequired (ATTR_OUTBOX_DIRECTORY);
    getAttributeAsStringRequired (ATTR_ERROR_DIRECTORY);
  }

  @Override
  public void poll ()
  {
    try
    {
      // scan the directory for new files
      scanDirectory (getAttributeAsStringRequired (ATTR_OUTBOX_DIRECTORY));

      // update tracking info. if a file is ready, process it
      updateTracking ();
    }
    catch (final Exception ex)
    {
      WrappedOpenAS2Exception.wrap (ex).terminate ();
      forceStop (ex);
    }
  }

  protected void scanDirectory (final String sDirectory) throws InvalidParameterException
  {
    final File aDir = AS2IOHelper.getDirectoryFile (sDirectory);

    // get a list of entries in the directory
    final File [] aFiles = aDir.listFiles ();
    if (aFiles == null)
    {
      throw new InvalidParameterException ("Error getting list of files in directory",
                                           this,
                                           ATTR_OUTBOX_DIRECTORY,
                                           aDir.getAbsolutePath ());
    }

    // iterator through each entry, and start tracking new files
    if (aFiles.length > 0)
      for (final File aCurrentFile : aFiles)
        if (checkFile (aCurrentFile))
        {
          // start watching the file's size if it's not already being watched
          trackFile (aCurrentFile);
        }
  }

  protected boolean checkFile (@Nonnull final File aFile)
  {
    if (aFile.exists () && aFile.isFile ())
    {
      FileOutputStream aFOS = null;
      try
      {
        // check for a write-lock on file, will skip file if it's write locked
        aFOS = new FileOutputStream (aFile, true);
        return true;
      }
      catch (final IOException ioe)
      {
        // a sharing violation occurred, ignore the file for now
      }
      finally
      {
        StreamHelper.close (aFOS);
      }
    }
    return false;
  }

  protected void trackFile (@Nonnull final File aFile)
  {
    final Map <String, Long> aTrackedFiles = trackedFiles ();
    final String sFilePath = aFile.getAbsolutePath ();
    if (!aTrackedFiles.containsKey (sFilePath))
      aTrackedFiles.put (sFilePath, Long.valueOf (aFile.length ()));
  }

  protected void updateTracking () throws OpenAS2Exception
  {
    // clone the trackedFiles map, iterator through the clone and modify the
    // original to avoid iterator exceptions
    // is there a better way to do this?
    final ICommonsMap <String, Long> aTrackedFiles = trackedFiles ();

    // We need to operate on a copy
    for (final Map.Entry <String, Long> aFileEntry : aTrackedFiles.getClone ().entrySet ())
    {
      // get the file and it's stored length
      final File aFile = new File (aFileEntry.getKey ());
      final long nFileLength = aFileEntry.getValue ().longValue ();

      // if the file no longer exists, remove it from the tracker
      if (!checkFile (aFile))
      {
        aTrackedFiles.remove (aFileEntry.getKey ());
      }
      else
      {
        // if the file length has changed, update the tracker
        final long nNewLength = aFile.length ();
        if (nNewLength != nFileLength)
        {
          aTrackedFiles.put (aFileEntry.getKey (), Long.valueOf (nNewLength));
        }
        else
        {
          // if the file length has stayed the same, process the file and stop
          // tracking it
          try
          {
            processFile (aFile);
          }
          finally
          {
            aTrackedFiles.remove (aFileEntry.getKey ());
          }
        }
      }
    }
  }

  protected void processFile (@Nonnull final File aFile) throws OpenAS2Exception
  {
    LOGGER.info ("processing " + aFile.getAbsolutePath ());

    final IMessage aMsg = createMessage ();
    aMsg.attrs ().putIn (CFileAttribute.MA_FILEPATH, aFile.getAbsolutePath ());
    aMsg.attrs ().putIn (CFileAttribute.MA_FILENAME, aFile.getName ());

    /*
     * asynch mdn logic 2007-03-12 save the file name into message object, it
     * will be stored into pending information file
     */
    aMsg.attrs ().putIn (CFileAttribute.MA_PENDING_FILENAME, aFile.getName ());

    if (LOGGER.isDebugEnabled ())
      LOGGER.debug ("AS2Message was created");

    try
    {
      updateMessage (aMsg, aFile);
      LOGGER.info ("file assigned to message " + aFile.getAbsolutePath () + aMsg.getLoggingText ());

      if (aMsg.getData () == null)
        throw new InvalidMessageException ("No Data");

      // Transmit the message
      getSession ().getMessageProcessor ().handle (IProcessorSenderModule.DO_SEND, aMsg, null);

      if (LOGGER.isDebugEnabled ())
        LOGGER.debug ("AS2Message was successfully handled my the MessageProcessor");

      /*
       * asynch mdn logic 2007-03-12 If the return status is pending in msg's
       * attribute "status" then copy the transmitted file to pending folder and
       * wait for the receiver to make another HTTP call to post AsyncMDN
       */
      if (CFileAttribute.MA_STATUS_PENDING.equals (aMsg.attrs ().getAsString (CFileAttribute.MA_STATUS)))
      {
        final File aPendingFile = new File (aMsg.partnership ().getAttribute (CFileAttribute.MA_STATUS_PENDING),
                                            aMsg.attrs ().getAsString (CFileAttribute.MA_PENDING_FILENAME));
        final FileIOError aIOErr = AS2IOHelper.getFileOperationManager ().copyFile (aFile, aPendingFile);
        if (aIOErr.isFailure ())
          throw new OpenAS2Exception ("File was successfully sent but not copied to pending folder: " +
                                      aPendingFile +
                                      " - " +
                                      aIOErr.toString ());

        LOGGER.info ("copied " +
                     aFile.getAbsolutePath () +
                     " to pending folder : " +
                     aPendingFile.getAbsolutePath () +
                     aMsg.getLoggingText ());
      }

      // If the Sent Directory option is set, move the transmitted file to
      // the sent directory
      if (attrs ().containsKey (ATTR_SENT_DIRECTORY))
      {
        File aSentFile = null;
        try
        {
          aSentFile = new File (AS2IOHelper.getDirectoryFile (getAttributeAsStringRequired (ATTR_SENT_DIRECTORY)),
                                aFile.getName ());
          aSentFile = AS2IOHelper.moveFile (aFile, aSentFile, false, true);

          LOGGER.info ("moved " +
                       aFile.getAbsolutePath () +
                       " to " +
                       aSentFile.getAbsolutePath () +
                       aMsg.getLoggingText ());

        }
        catch (final IOException ex)
        {
          final OpenAS2Exception se = new OpenAS2Exception ("File was successfully sent but not moved to sent folder: " +
                                                            aSentFile);
          se.initCause (ex);
        }
      }
      else
      {
        if (LOGGER.isDebugEnabled ())
          LOGGER.debug ("Trying to delete file " + aFile.getAbsolutePath ());

        if (!aFile.delete ())
        {
          // Delete the file if a sent directory isn't set
          throw new OpenAS2Exception ("File was successfully sent but not deleted: " + aFile);
        }
        LOGGER.info ("deleted " + aFile.getAbsolutePath () + aMsg.getLoggingText ());
      }
    }
    catch (final OpenAS2Exception ex)
    {
      ex.setSourceMsg (aMsg).setSourceFile (aFile).terminate ();
      AS2IOHelper.handleError (aFile, getAttributeAsStringRequired (ATTR_ERROR_DIRECTORY));
    }
  }

  @Nonnull
  protected abstract IMessage createMessage ();

  public void updateMessage (@Nonnull final IMessage aMsg, @Nonnull final File aFile) throws OpenAS2Exception
  {
    final MessageParameters aParams = new MessageParameters (aMsg);

    final String sDefaults = attrs ().getAsString (ATTR_DEFAULTS);
    if (sDefaults != null)
      aParams.setParameters (sDefaults);

    final String sFilename = aFile.getName ();
    final String sFormat = attrs ().getAsString (ATTR_FORMAT);
    if (sFormat != null)
    {
      final String sDelimiters = attrs ().getAsString (ATTR_DELIMITERS, ".-");
      aParams.setParameters (sFormat, sDelimiters, sFilename);
    }

    try
    {
      final byte [] aData = SimpleFileIO.getAllFileBytes (aFile);
      String sContentType = attrs ().getAsString (ATTR_MIMETYPE);
      if (sContentType == null)
      {
        // Default to application/octet-stream
        sContentType = CMimeType.APPLICATION_OCTET_STREAM.getAsString ();
      }
      else
      {
        try
        {
          sContentType = aParams.format (sContentType);
        }
        catch (final InvalidParameterException ex)
        {
          LOGGER.error ("Bad content-type '" + sContentType + "'" + aMsg.getLoggingText ());
          // Default to application/octet-stream
          sContentType = CMimeType.APPLICATION_OCTET_STREAM.getAsString ();
        }
      }

      final ByteArrayDataSource aByteSource = new ByteArrayDataSource (aData, sContentType, null);
      final MimeBodyPart aBody = new MimeBodyPart ();
      aBody.setDataHandler (aByteSource.getAsDataHandler ());

      // Headers must be set AFTER the DataHandler
      final String sCTE = aMsg.partnership ()
                              .getContentTransferEncodingSend (EContentTransferEncoding.AS2_DEFAULT.getID ());
      aBody.setHeader (CHttpHeader.CONTENT_TRANSFER_ENCODING, sCTE);

      // below statement is not filename related, just want to make it
      // consist with the parameter "mimetype="application/EDI-X12""
      // defined in config.xml 2007-06-01
      aBody.setHeader (CHttpHeader.CONTENT_TYPE, sContentType);

      // add below statement will tell the receiver to save the filename
      // as the one sent by sender. 2007-06-01
      final String sSendFilename = attrs ().getAsString (ATTR_SENDFILENAME);
      if ("true".equals (sSendFilename))
      {
        final String sMAFilename = aMsg.attrs ().getAsString (CFileAttribute.MA_FILENAME);
        final String sContentDisposition = "Attachment; filename=\"" + sMAFilename + "\"";
        aBody.setHeader (CHttpHeader.CONTENT_DISPOSITION, sContentDisposition);
        aMsg.setContentDisposition (sContentDisposition);
      }

      aMsg.setData (aBody);
    }
    catch (final MessagingException ex)
    {
      throw WrappedOpenAS2Exception.wrap (ex);
    }

    if (LOGGER.isDebugEnabled ())
      LOGGER.debug ("Updating partnership for AS2 message" + aMsg.getLoggingText ());

    // update the message's partnership with any stored information
    getSession ().getPartnershipFactory ().updatePartnership (aMsg, true);

    if (LOGGER.isDebugEnabled ())
      LOGGER.debug ("Finished updating partnership for AS2 message");

    aMsg.updateMessageID ();

    if (LOGGER.isDebugEnabled ())
      LOGGER.debug ("Updated message ID to " + aMsg.getMessageID ());
  }

  @Nonnull
  @ReturnsMutableObject
  public ICommonsMap <String, Long> trackedFiles ()
  {
    if (m_aTrackedFiles == null)
      m_aTrackedFiles = new CommonsHashMap <> ();
    return m_aTrackedFiles;
  }
}
