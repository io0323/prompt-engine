// P11 性能測定（NFR-003、README「性能測定」節）専用のローカル開発用JWKS+JWT発行ツール。
//
// SecurityConfig（modules/prompt-engine-bootstrap）の既定JwtDecoderは、pe.ciap.jwks-uri未設定時
// 秘密鍵を一切保持しない自己署名フォールバックへ倒れる設計になっている（実運用での誤発行防止、
// SecurityConfig.devPublicKeyのKDoc参照）。このため、ローカルでコンテナに対して実際に認証付き
// HTTPリクエストを送るには、pe.ciap.jwks-uri（PE_CIAP_JWKS_URI）に「秘密鍵を保持する」JWKS
// エンドポイントを明示的に向ける必要がある。本ツールはその代替（テスト専用の使い捨て鍵ペア）。
//
// 実運用のJWKSエンドポイントの代わりに使うことは想定していない。生成する鍵ペアは起動の度に
// 使い捨てで、有効期限も6時間に固定している。
//
// 実行例:
//   java DevJwks.java 8099
//   → 標準出力にJWT（Authorizationヘッダ用）とJWKS_URI（PE_CIAP_JWKS_URI用、
//     host.docker.internal経由でDockerコンテナから到達可能）を出力し、
//     Ctrl+Cで停止するまで/jwks.jsonを配信し続ける。

import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

public class DevJwks {
    private static final String DEFAULT_SCOPES =
        "prompt:read prompt:write prompt:review prompt:approve prompt:publish prompt:execute audit:read";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args.length > 0 ? args[0] : "8099");
        String scopes = args.length > 1 ? args[1] : DEFAULT_SCOPES;

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        String keyId = "dev-perf-key";

        String modulus = base64Url(publicKey.getModulus());
        String exponent = base64Url(publicKey.getPublicExponent());
        String jwks =
            "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + keyId
                + "\",\"n\":\"" + modulus + "\",\"e\":\"" + exponent + "\"}]}";

        long issuedAt = System.currentTimeMillis() / 1000L;
        long expiresAt = issuedAt + 6 * 3600;
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + keyId + "\"}";
        String payload =
            "{\"sub\":\"perf-test\",\"scope\":\"" + scopes + "\",\"iat\":" + issuedAt + ",\"exp\":" + expiresAt + "}";
        String signingInput =
            base64Url(header.getBytes(StandardCharsets.UTF_8)) + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String jwt = signingInput + "." + base64Url(signature.sign());

        System.out.println("JWT=" + jwt);
        System.out.println("JWKS_URI=http://host.docker.internal:" + port + "/jwks.json");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/jwks.json", exchange -> {
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.out.println("JWKS server listening on port " + port + " (Ctrl+C to stop)");
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return base64Url(bytes);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
