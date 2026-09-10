package com.razorpay.refund;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.policy.StandardRefundPolicy;
import com.razorpay.refund.service.RefundBreakdown;
import com.razorpay.refund.service.RefundCalculator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaywrightSmokeTest {

    @Test
    void chromiumRendersRefundSummary() throws Exception {
        RefundCalculator calculator = new RefundCalculator(new StandardRefundPolicy());
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));
        RefundBreakdown breakdown = calculator.calculateFullReturn(Money.of("700.00"), allocation);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/smoke", exchange -> respondWithHtml(exchange, html(breakdown)));
        server.start();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel("chrome")
                    .setHeadless(true));

            Page page = browser.newPage();
            page.navigate("http://127.0.0.1:" + server.getAddress().getPort() + "/smoke");

            assertTrue(page.locator("h1").textContent().contains("Refund Smoke Test"));
            assertTrue(page.locator("[data-testid='total-refund']").textContent().contains("686.00"));
            assertTrue(page.locator("[data-testid='wallet-refund']").textContent().contains("294.00"));
            assertTrue(page.locator("[data-testid='online-refund']").textContent().contains("392.00"));

            browser.close();
        } finally {
            server.stop(0);
        }
    }

    private static void respondWithHtml(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String html(RefundBreakdown breakdown) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Refund Smoke Test</title>
                </head>
                <body>
                  <main>
                    <h1>Refund Smoke Test</h1>
                    <p data-testid="total-refund">Total refund: %s</p>
                    <p data-testid="wallet-refund">Wallet refund: %s</p>
                    <p data-testid="online-refund">Online refund: %s</p>
                  </main>
                </body>
                </html>
                """.formatted(
                breakdown.getTotalCustomerRefund(),
                breakdown.getWalletRefund(),
                breakdown.getMerchantOnlineRefund()
        );
    }
}
