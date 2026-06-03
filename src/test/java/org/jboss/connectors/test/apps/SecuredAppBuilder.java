package org.jboss.connectors.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.net.URL;

/**
 * Builds {@code secured.war} at runtime using ShrinkWrap.
 * Packages {@link SecuredServlet} with a {@code web.xml} that declares the
 * {@code EXTERNAL} auth method and a {@code jboss-web.xml} that maps to the
 * {@code ajp-auth-domain} application security domain.
 *
 * <p>The WAR is written to the system temp directory and deleted on JVM exit.
 */
public class SecuredAppBuilder {

    public static File createSecuredApp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL webXml = cl.getResource("apps/secured/web.xml");
        URL jbossWebXml = cl.getResource("apps/secured/jboss-web.xml");

        final WebArchive war = ShrinkWrap.create(WebArchive.class, "secured.war")
                .addClass(SecuredServlet.class)
                .setWebXML(webXml)
                .addAsWebInfResource(jbossWebXml, "jboss-web.xml");

        final File tempWar = new File(System.getProperty("java.io.tmpdir"), "secured.war");
        war.as(ZipExporter.class).exportTo(tempWar, true);
        tempWar.deleteOnExit();

        return tempWar;
    }
}
