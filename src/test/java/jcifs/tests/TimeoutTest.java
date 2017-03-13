/*
 * © 2016 AgNO3 Gmbh & Co. KG
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package jcifs.tests;


import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.net.ServerSocketFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import jcifs.CIFSContext;
import jcifs.config.DelegatingConfiguration;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.util.transport.ConnectionTimeoutException;


/**
 * @author mbechler
 *
 */
@RunWith ( Parameterized.class )
@SuppressWarnings ( "javadoc" )
public class TimeoutTest extends BaseCIFSTest {

    public TimeoutTest ( String name, Map<String, String> properties ) {
        super(name, properties);
    }


    protected CIFSContext lowTimeout ( CIFSContext ctx ) {
        return withConfig(ctx, new DelegatingConfiguration(ctx.getConfig()) {

            @Override
            public int getSoTimeout () {
                return 100;
            }
        });

    }


    protected CIFSContext lowConnectTimeout ( CIFSContext ctx ) {
        return withConfig(ctx, new DelegatingConfiguration(ctx.getConfig()) {

            /**
             * {@inheritDoc}
             *
             * @see jcifs.config.DelegatingConfiguration#getResponseTimeout()
             */
            @Override
            public int getResponseTimeout () {
                return 100;
            }


            @Override
            public int getConnTimeout () {
                return 100;
            }
        });
    }


    @Test
    public void testTimeoutOpenFile () throws IOException, InterruptedException {
        SmbFile f = new SmbFile(new SmbFile(getTestShareURL(), lowTimeout(withTestNTLMCredentials(getContext()))), makeRandomName());
        f.createNewFile();
        try {
            try ( OutputStream os = f.getOutputStream() ) {
                os.write(new byte[] {
                    1, 2, 3, 4
                });
            }

            try ( InputStream is = f.getInputStream() ) {
                is.read();
                Thread.sleep(100);
                is.read();
                Thread.sleep(100);
                is.read();
                Thread.sleep(100);
                is.read();
                Thread.sleep(100);
            }
        }
        finally {
            f.delete();
        }
    }


    @Test ( expected = ConnectionTimeoutException.class )
    public void testConnectTimeoutRead () throws IOException {
        Set<Thread> threadsBefore = new HashSet<>(Thread.getAllStackTraces().keySet());
        try ( ServerSocket ss = ServerSocketFactory.getDefault().createServerSocket(0, -1, InetAddress.getLoopbackAddress()) ) {
            int port = ss.getLocalPort();
            InetAddress addr = ss.getInetAddress();

            long start = System.currentTimeMillis();
            CIFSContext ctx = lowConnectTimeout(getContext());
            SmbFile f = new SmbFile(new URL("smb", addr.getHostAddress(), port, "/" + getTestShare() + "/connect.test", ctx.getUrlHandler()), ctx);
            runConnectTimeoutTest(threadsBefore, start, ctx, f);
        }
    }


    @Test ( expected = ConnectionTimeoutException.class )
    public void testConnectTimeout () throws IOException {
        Set<Thread> threadsBefore = new HashSet<>(Thread.getAllStackTraces().keySet());
        long start = System.currentTimeMillis();
        CIFSContext ctx = lowConnectTimeout(getContext());

        SmbFile f = new SmbFile(new URL("smb", "10.255.255.1", 139, "/" + getTestShare() + "/connect.test", ctx.getUrlHandler()), ctx);
        runConnectTimeoutTest(threadsBefore, start, ctx, f);
    }


    /**
     * @param threadsBefore
     * @param start
     * @param ctx
     * @param f
     * @throws ConnectionTimeoutException
     * @throws SmbException
     */
    void runConnectTimeoutTest ( Set<Thread> threadsBefore, long start, CIFSContext ctx, SmbFile f ) throws ConnectionTimeoutException, SmbException {
        try {
            f.createNewFile();
            assertTrue("Did not see error", false);
        }
        catch ( SmbException e ) {
            if ( e.getCause() instanceof ConnectionTimeoutException ) {
                long timeout = System.currentTimeMillis() - start;
                assertTrue(
                    String.format(
                        "Timeout %d outside expected range (%f)",
                        timeout,
                        1.5 * ( ctx.getConfig().getConnTimeout() + ctx.getConfig().getResponseTimeout() )),
                    timeout < 1.5 * ( ctx.getConfig().getConnTimeout() + ctx.getConfig().getResponseTimeout() ));

                Set<Thread> threadsAfter = new HashSet<>(Thread.getAllStackTraces().keySet());
                threadsAfter.removeAll(threadsBefore);

                Set<Thread> leaked = new HashSet<>();
                for ( Thread t : threadsAfter ) {
                    if ( t.getName().startsWith("Transport") ) {
                        leaked.add(t);
                    }
                }
                assertTrue("Leaked transport threads, have " + leaked, leaked.size() == 0);
                throw (ConnectionTimeoutException) e.getCause();
            }
            throw e;
        }
    }

}