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
package com.helger.as2lib.processor.sender;

import java.io.File;
import java.net.Proxy;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import com.helger.as2lib.exception.OpenAS2Exception;
import com.helger.as2lib.message.IBaseMessage;
import com.helger.as2lib.util.AS2IOHelper;
import com.helger.as2lib.util.dump.HTTPOutgoingDumperFileBased;
import com.helger.as2lib.util.dump.IHTTPOutgoingDumper;
import com.helger.as2lib.util.http.AS2HttpClient;
import com.helger.as2lib.util.http.IHTTPOutgoingDumperFactory;
import com.helger.commons.ValueEnforcer;
import com.helger.commons.annotation.Nonempty;
import com.helger.commons.annotation.OverrideOnDemand;
import com.helger.commons.http.EHttpMethod;
import com.helger.commons.string.StringHelper;
import com.helger.commons.system.SystemProperties;
import com.helger.commons.url.EURLProtocol;
import com.helger.commons.ws.HostnameVerifierVerifyAll;
import com.helger.commons.ws.TrustManagerTrustAll;

/**
 * Abstract HTTP based sender module
 *
 * @author Philip Helger
 */
public abstract class AbstractHttpSenderModule extends AbstractSenderModule
{
  /** Attribute name for connection timeout in milliseconds */
  public static final String ATTR_CONNECT_TIMEOUT = "connecttimeout";
  /** Attribute name for read timeout in milliseconds */
  public static final String ATTR_READ_TIMEOUT = "readtimeout";
  /** Attribute name for quoting header values (boolean) */
  public static final String ATTR_QUOTE_HEADER_VALUES = "quoteheadervalues";

  /** Default connection timeout: 60 seconds */
  public static final int DEFAULT_CONNECT_TIMEOUT_MS = 60_000;
  /** Default read timeout: 60 seconds */
  public static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
  /** Default quote header values: false */
  public static final boolean DEFAULT_QUOTE_HEADER_VALUES = false;

  private static final class OutgoingDumperFactory implements IHTTPOutgoingDumperFactory
  {
    // Counter to ensure unique filenames
    private final AtomicInteger m_aCounter = new AtomicInteger (0);
    private final File m_aDumpDirectory;

    public OutgoingDumperFactory (@Nonnull final File aDumpDirectory)
    {
      m_aDumpDirectory = aDumpDirectory;
    }

    @Nonnull
    public IHTTPOutgoingDumper apply (@Nonnull final IBaseMessage aMsg)
    {
      return new HTTPOutgoingDumperFileBased (new File (m_aDumpDirectory,
                                                        "as2-outgoing-" +
                                                                          Long.toString (System.currentTimeMillis ()) +
                                                                          "-" +
                                                                          Integer.toString (m_aCounter.getAndIncrement ()) +
                                                                          ".http"));
    }
  }

  private static final IHTTPOutgoingDumperFactory DEFAULT_HTTP_OUTGOING_DUMPER_FACTORY;

  static
  {
    // Set global outgoing dump directory (since v4.0.3)
    // This is contained for backwards compatibility only
    final String sHttpDumpOutgoingDirectory = SystemProperties.getPropertyValueOrNull ("AS2.httpDumpDirectoryOutgoing");
    if (StringHelper.hasText (sHttpDumpOutgoingDirectory))
    {
      final File aDumpDirectory = new File (sHttpDumpOutgoingDirectory);
      AS2IOHelper.getFileOperationManager ().createDirIfNotExisting (aDumpDirectory);
      DEFAULT_HTTP_OUTGOING_DUMPER_FACTORY = new OutgoingDumperFactory (aDumpDirectory);
    }
    else
      DEFAULT_HTTP_OUTGOING_DUMPER_FACTORY = null;
  }

  private IHTTPOutgoingDumperFactory m_aHttpOutgoingDumperFactory = DEFAULT_HTTP_OUTGOING_DUMPER_FACTORY;

  public AbstractHttpSenderModule ()
  {}

  @Nullable
  public final IHTTPOutgoingDumperFactory getHttpOutgoingDumperFactory ()
  {
    return m_aHttpOutgoingDumperFactory;
  }

  @Nullable
  public final IHTTPOutgoingDumper getHttpOutgoingDumper (@Nonnull final IBaseMessage aMsg)
  {
    return m_aHttpOutgoingDumperFactory == null ? null : m_aHttpOutgoingDumperFactory.apply (aMsg);
  }

  public final void setHttpOutgoingDumperFactory (@Nullable final IHTTPOutgoingDumperFactory aHttpOutgoingDumperFactory)
  {
    m_aHttpOutgoingDumperFactory = aHttpOutgoingDumperFactory;
  }

  /**
   * Create the {@link SSLContext} to be used for https connections. By default
   * the SSL context will trust all hosts and present no keys. Override this
   * method in a subclass to customize this handling.
   *
   * @return The created {@link SSLContext}. May not be <code>null</code>.
   * @throws GeneralSecurityException
   *         If something internally goes wrong.
   */
  @Nonnull
  @OverrideOnDemand
  public SSLContext createSSLContext () throws GeneralSecurityException
  {
    // Trust all server certificates
    final SSLContext aSSLCtx = SSLContext.getInstance ("TLS");
    aSSLCtx.init (null, new TrustManager [] { new TrustManagerTrustAll () }, null);
    return aSSLCtx;
  }

  /**
   * Get the hostname verifier to be used. By default an instance of
   * {@link HostnameVerifierVerifyAll} is returned. Override this method to
   * change this default behavior.
   *
   * @return The hostname verifier to be used. If the returned value is
   *         <code>null</code> it will not be applied to the https connection.
   */
  @Nullable
  @OverrideOnDemand
  public HostnameVerifier createHostnameVerifier ()
  {
    return new HostnameVerifierVerifyAll ();
  }

  /**
   * Generate a HttpClient connection. It works with streams and avoids holding
   * whole messge in memory. note that bOutput, bInput, and bUseCaches are not
   * supported
   *
   * @param sUrl
   *        URL to connect to
   * @param eRequestMethod
   *        HTTP Request method to use. May not be <code>null</code>.
   * @param aProxy
   *        Optional proxy to use. May be <code>null</code>.
   * @return a {@link AS2HttpClient} object to work with
   * @throws OpenAS2Exception
   *         If something goes wrong
   */
  @Nonnull
  public AS2HttpClient getHttpClient (@Nonnull @Nonempty final String sUrl,
                                      @Nonnull final EHttpMethod eRequestMethod,
                                      @Nullable final Proxy aProxy) throws OpenAS2Exception
  {
    ValueEnforcer.notEmpty (sUrl, "URL");
    SSLContext aSSLCtx = null;
    HostnameVerifier aHV = null;
    if (EURLProtocol.HTTPS.isUsedInURL (sUrl.toLowerCase (Locale.ROOT)))
    {
      // Create SSL context and HostnameVerifier
      try
      {
        aSSLCtx = createSSLContext ();
      }
      catch (final GeneralSecurityException ex)
      {
        throw new OpenAS2Exception ("Error creating SSL Context", ex);
      }
      aHV = createHostnameVerifier ();
    }
    final int nConnectTimeoutMS = attrs ().getAsInt (ATTR_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT_MS);
    final int nReadTimeoutMS = attrs ().getAsInt (ATTR_READ_TIMEOUT, DEFAULT_READ_TIMEOUT_MS);
    return new AS2HttpClient (sUrl, nConnectTimeoutMS, nReadTimeoutMS, eRequestMethod, aProxy, aSSLCtx, aHV);
  }
}
