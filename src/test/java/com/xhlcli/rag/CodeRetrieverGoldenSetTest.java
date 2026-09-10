package com.xhlcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 黄金测试集（Golden Set）：验证自然语言到代码模块/方法的端到端混合检索与增量维护能力
 */
class CodeRetrieverGoldenSetTest {

    @TempDir
    Path tempDir;

    private CodeIndex indexer;
    private CodeRetriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("xhlcli.rag.dir", tempDir.resolve(".xhlcli").resolve("rag").toString());
        System.setProperty("xhlcli.embedding.provider", "fake");

        // 构建跨模块测试代码树
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);

        Files.writeString(src.resolve("AuthService.java"), """
                package com.example.auth;
                
                public class AuthService {
                    public boolean authenticateUser(String username, String password) {
                        // 校验用户账号与密码实现身份验证
                        return "admin".equals(username) && "secret".equals(password);
                    }
                    
                    public void logout(String token) {
                        System.out.println("用户登出");
                    }
                }
                """);

        Files.writeString(src.resolve("OrderService.java"), """
                package com.example.order;
                
                import com.example.payment.PaymentClient;
                
                public class OrderService {
                    private PaymentClient paymentClient;
                    
                    public String createAndPayOrder(String orderId, double amount) {
                        // 创建订单并调用支付渠道进行结算
                        return paymentClient.processPayment(orderId, amount);
                    }
                }
                """);

        Files.writeString(src.resolve("PaymentClient.java"), """
                package com.example.payment;
                
                public class PaymentClient {
                    public String processPayment(String orderId, double amount) {
                        // 处理在线扣款与第三方支付网关通信
                        return "SUCCESS:" + orderId;
                    }
                }
                """);

        indexer = new CodeIndex(EmbeddingClient.fake(), CodeIndex.ProgressListener.noop());
        // 执行初次索引
        CodeIndex.IndexResult res = indexer.index(tempDir.toString(), true);
        assertTrue(res.chunkCount() > 0);
        assertTrue(res.updatedFiles() >= 3);

        retriever = new CodeRetriever(tempDir.toString(), EmbeddingClient.fake());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (retriever != null) {
            retriever.close();
        }
    }

    @Test
    void testGoldenQueryAuthUser() throws Exception {
        List<VectorStore.SearchResult> results = retriever.hybridSearch("用户账号密码身份验证authenticateUser", 3);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).name().contains("authenticateUser"));
        assertTrue(results.get(0).filePath().contains("AuthService.java"));
    }

    @Test
    void testGoldenQueryPaymentProcess() throws Exception {
        List<VectorStore.SearchResult> results = retriever.hybridSearch("在线扣款与第三方支付网关通信processPayment", 3);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).name().contains("processPayment") || results.get(0).content().contains("processPayment"));
    }

    @Test
    void testIncrementalIndexAndSearchRecall() throws Exception {
        // 修改 AuthService.java，添加新方法 refreshToken
        Path authFile = tempDir.resolve("src").resolve("AuthService.java");
        Files.writeString(authFile, """
                package com.example.auth;
                
                public class AuthService {
                    public boolean authenticateUser(String username, String password) {
                        return true;
                    }
                    
                    public String refreshToken(String oldToken) {
                        // 刷新用户登录会话令牌 token
                        return "NEW_TOKEN_" + oldToken;
                    }
                }
                """);

        // 执行增量索引
        CodeIndex.IndexResult updateRes = indexer.index(tempDir.toString(), false);
        assertEquals(1, updateRes.updatedFiles(), "应只更新一个文件");
        assertTrue(updateRes.skippedFiles() >= 2, "其它未修改文件应跳过");

        // 验证对新添加的方法能立即精准检索
        List<VectorStore.SearchResult> results = retriever.hybridSearch("刷新用户登录会话令牌refreshToken", 3);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).name().contains("refreshToken") || results.get(0).content().contains("refreshToken"));
    }
}
