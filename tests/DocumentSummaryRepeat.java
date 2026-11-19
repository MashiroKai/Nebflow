import java.io.IOException;

/** Run the real Python integration suite serially, without retries, under one JVM. */
class DocumentSummaryRepeat {
    public static void main(String[] args) throws IOException, InterruptedException {
        long pid = ProcessHandle.current().pid();
        for (int i = 1; i <= 20; i++) {
            Process process = new ProcessBuilder("python3", "-B", "tests/test_document_summary_terms.py")
                    .inheritIO().start();
            int status = process.waitFor();
            if (status != 0) {
                System.err.println("FAIL iteration=" + i + " jvm_pid=" + pid + " exit=" + status);
                System.exit(status);
            }
            System.out.println("PASS iteration=" + i + " jvm_pid=" + pid);
        }
    }
}
