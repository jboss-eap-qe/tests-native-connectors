package org.jboss.connectors.test.apps;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.File;
import java.net.URL;

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
