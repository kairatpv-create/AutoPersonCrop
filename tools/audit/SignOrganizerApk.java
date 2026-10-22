import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Local signing only. A Properties document containing key path/alias/password arrives on stdin.
 * Never put a private key, password, or signed download URL in Git or a CI workflow.
 */
public final class SignOrganizerApk {
    private static final String EXPECTED="a0564fdcf4e365a0077cbb0f56cf4ddce9538a45ba772b1793ab3f549b8f90b2";
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected input staging APK and new output APK paths");
        File input=new File(args[0]), output=new File(args[1]);
        if(!input.isFile()||output.exists()) throw new IllegalArgumentException("Input missing or output already exists");
        Properties config=new Properties(); config.load(System.in);
        char[] password=config.getProperty("password").toCharArray();
        try {
            KeyStore store=KeyStore.getInstance(new File(config.getProperty("keystore")),password);
            String alias=config.getProperty("alias");
            X509Certificate certificate=(X509Certificate)store.getCertificate(alias);
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if(!EXPECTED.equals(fingerprint)) throw new SecurityException("The key does not match the installed Organizer Pro certificate");
            PrivateKey key=(PrivateKey)store.getKey(alias,password);
            var signer=new ApkSigner.SignerConfig.Builder("OrganizerPro",key,List.of(certificate)).build();
            new ApkSigner.Builder(List.of(signer)).setInputApk(input).setOutputApk(output)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setV4SigningEnabled(false).setOtherSignersSignaturesPreserved(false)
                .setAlignmentPreserved(true).setLibraryPageAlignmentBytes(16384).build().sign();
            var verification=new ApkVerifier.Builder(output).build().verify();
            if(!verification.isVerified()||!verification.isVerifiedUsingV2Scheme()||!verification.isVerifiedUsingV3Scheme()
                ||verification.getSignerCertificates().size()!=1
                ||!Arrays.equals(certificate.getEncoded(),verification.getSignerCertificates().get(0).getEncoded()))
                throw new SecurityException("Final APK signature verification failed");
            System.out.println("Verified original Organizer Pro certificate: "+fingerprint);
        } finally { Arrays.fill(password,'\0');config.clear(); }
    }
}
