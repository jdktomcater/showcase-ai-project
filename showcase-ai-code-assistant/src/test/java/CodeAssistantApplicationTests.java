import com.jdktomcat.showcase.ai.code.assistant.CodeAssistantApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = CodeAssistantApplication.class)
// 测试结束后自动回滚事务，避免污染数据库
public class CodeAssistantApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void testAnalysisWorkflow() {
    }
}
