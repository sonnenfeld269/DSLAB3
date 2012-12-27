/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package security;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Scanner;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author sanker
 */
public class RSAAuthenticationTest {
    
    String ServerkeyDirectory=null; 
    String ClientkeyDirectory=null;
    public static InputStream stdin=System.in;
   // ByteArrayInputStream stream
    public RSAAuthenticationTest() {
        ServerkeyDirectory="./keys/Server/";
        ClientkeyDirectory="./keys/Clients/";
        
        
        String data = "23456\n";

        System.setIn(new ByteArrayInputStream(data.getBytes()));
        //Scanner scanner = new Scanner(System.in);
        //System.out.println(scanner.nextLine());
        
    }
    
    @BeforeClass
    public static void setUpClass() {
    }
    
    @AfterClass
    public static void tearDownClass() {
        System.setIn(stdin);
    }
    
    @Before
    public void setUp() {
    }
    
    @After
    public void tearDown() {
    }

    /**
     * Test of getPublicKey method, of class RSAAuthentication.
     */
    @Test
    public void testGetServerPublicKey() throws Exception {
        System.out.println("getPublicKey from Server:");
      
        RSAAuthentication instance = new RSAAuthentication(ClientkeyDirectory,
                ServerkeyDirectory);
        String file = instance.getServerKeyDirectorypath()+"/auction-server.pub.pem";
        PublicKey expResult = null;
        PublicKey result = instance.getPublicKey(file);
        
        assertNotNull(result);
        System.out.println("Server PublicKey:");
        System.out.println(result);
        // TODO review the generated test code and remove the default call to fail.
       // fail("The test case is a prototype.");
    }

    /**
     * Test of getPrivateKey method, of class RSAAuthentication.
     */
    @Test
    public void testServerGetPrivateKey() throws Exception {
        System.out.println("getPrivateKey from Server");
        RSAAuthentication instance = new RSAAuthentication(ClientkeyDirectory,
                ServerkeyDirectory);
        String file = instance.getServerKeyDirectorypath()+"/auction-server.pem";
        PrivateKey expResult = null;
        PrivateKey result = instance.getPrivateKey(file);
        //assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        //fail("The test case is a prototype.");
        assertNotNull(result);
        System.out.println("Server Private Key:");
        System.out.println(result);
    }
}