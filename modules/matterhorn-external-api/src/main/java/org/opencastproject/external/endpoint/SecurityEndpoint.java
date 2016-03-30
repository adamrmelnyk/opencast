/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.opencastproject.external.endpoint;

import static com.entwinemedia.fn.data.json.Jsons.f;
import static com.entwinemedia.fn.data.json.Jsons.j;
import static com.entwinemedia.fn.data.json.Jsons.v;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.opencastproject.external.common.ApiVersion.VERSION_1_0_0;
import static org.opencastproject.util.DateTimeSupport.fromUTC;
import static org.opencastproject.util.DateTimeSupport.toUTC;

import org.opencastproject.external.common.ApiResponses;
import org.opencastproject.security.urlsigning.exception.UrlSigningException;
import org.opencastproject.security.urlsigning.service.UrlSigningService;
import org.opencastproject.util.Log;
import org.opencastproject.util.OsgiUtil;
import org.opencastproject.util.RestUtil.R;

import com.entwinemedia.fn.data.Opt;

import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.Date;
import java.util.Dictionary;

import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

@Path("/")
public class SecurityEndpoint implements ManagedService {

  protected static final String URL_SIGNING_EXPIRES_DURATION_SECONDS_KEY = "url.signing.expires.seconds";

  /** The default time before a piece of signed content expires. 2 Hours. */
  protected static final long DEFAULT_URL_SIGNING_EXPIRE_DURATION = 2 * 60 * 60;

  /** The logging facility */
  private static final Logger log = LoggerFactory.getLogger(SecurityEndpoint.class);

  private long expireSeconds = DEFAULT_URL_SIGNING_EXPIRE_DURATION;

  /* OSGi service references */
  private UrlSigningService urlSigningService;

  /** OSGi DI */
  void setUrlSigningService(UrlSigningService urlSigningService) {
    this.urlSigningService = urlSigningService;
  }

  /** OSGi activation method */
  void activate() {
    log.info("Activating External API - Security Endpoint");
  }

  @Override
  public void updated(@SuppressWarnings("rawtypes") Dictionary properties) throws ConfigurationException {
    Opt<Long> expiration = OsgiUtil.getOptCfg(properties, URL_SIGNING_EXPIRES_DURATION_SECONDS_KEY).toOpt()
            .map(com.entwinemedia.fn.fns.Strings.toLongF);
    if (expiration.isSome()) {
      expireSeconds = expiration.get();
      log.info("The property {} has been configured to expire signed URLs in {}.",
              URL_SIGNING_EXPIRES_DURATION_SECONDS_KEY, Log.getHumanReadableTimeString(expireSeconds));
    } else {
      expireSeconds = DEFAULT_URL_SIGNING_EXPIRE_DURATION;
      log.info(
              "The property {} has not been configured, so the default is being used to expire signed URLs in {}.",
              URL_SIGNING_EXPIRES_DURATION_SECONDS_KEY, Log.getHumanReadableTimeString(expireSeconds));
    }
  }

  @POST
  @Path("sign")
  @Produces({ "application/json", "application/v1.0.0+json" })
  public Response signUrl(@HeaderParam("Accept") String acceptHeader, @FormParam("url") String url,
          @FormParam("valid-until") String validUntilUtc, @FormParam("valid-source") String validSource) {

    if (isBlank(url))
      return R.badRequest("Query parameter 'url' is mandatory");

    final DateTime validUntil;
    if (isNotBlank(validUntilUtc)) {
      try {
        validUntil = new DateTime(fromUTC(validUntilUtc));
      } catch (IllegalStateException | ParseException e) {
        return R.badRequest("Query parameter 'valid-until' is not a valid ISO-8601 date string");
      }
    } else {
      validUntil = new DateTime(new Date().getTime() + expireSeconds * DateTimeConstants.MILLIS_PER_SECOND);
    }

    if (urlSigningService.accepts(url)) {
      String signedUrl = "";
      try {
        signedUrl = urlSigningService.sign(url, validUntil, null, validSource);
      } catch (UrlSigningException e) {
        log.warn("Error while trying to sign url '{}': {}", url, getStackTrace(e));
        return ApiResponses.Json.ok(VERSION_1_0_0, j(f("error", v("Error while signing url"))));
      }
      return ApiResponses.Json.ok(VERSION_1_0_0,
              j(f("url", v(signedUrl)), f("valid-until", v(toUTC(validUntil.getMillis())))));
    } else {
      return ApiResponses.Json.ok(VERSION_1_0_0, j(f("error", v("Given URL cannot be signed"))));
    }
  }
}
