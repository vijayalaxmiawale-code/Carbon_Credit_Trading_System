import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public class CarbonCreditSystem {

    // =========================================================================
    //  SECTION 1 — CONSTANTS & ENUMS
    // =========================================================================

    enum Domain {
        MANUFACTURING (2.5, "Heavy industrial processes (steel, cement)"),
        ENERGY        (3.0, "Power generation — highest emitter class"),
        TRANSPORT     (1.8, "Road, rail, aviation logistics"),
        IT            (0.6, "Data centres and digital infrastructure"),
        AGRICULTURE   (1.2, "Farming, livestock, land use"),
        OTHER         (1.5, "General / unclassified sector");

        final double multiplier;
        final String description;
        Domain(double m, String d) { multiplier = m; description = d; }
    }

    // Fixed price constants — kept from v2.0
   static final double BASE_CREDIT_PRICE = 1500.0;
  static final double MIN_PRICE = 500.0;
  static final double MAX_PRICE = 5000.0;
  static final int    MAX_REQUESTS      = 3;

    // Admin credentials — hardcoded for simplicity (interview note: in real
    // systems this would be stored as a hash in a secure config)
    static final String ADMIN_ID       = "admin";
    static final String ADMIN_PASSWORD = "admin123";

    // Credits-per-tonne: 1 tonne under limit = 1 credit earned
    // Emission data inputs -> system calculates credits automatically
    static final double CREDIT_PER_TONNE = 1.0;

    // =========================================================================
    //  SECTION 2 — COMPANY MODEL
    // =========================================================================

    static class Company implements Comparable<Company> {
        String  companyId;
        String  name;
        String  passwordHash;
        String  salt;
        Domain  domain;

        int     employees;
        double  emissionLimit;   // calculated: employees * domain.multiplier * 10
        double  currentEmission; // set by monthly emission update
        double  credits;         // calculated automatically from emission data
        int     requestCount;    // buy-request counter (max 3 per cycle)
        boolean isBlocked;

        // Contact info for Profile Management (Section 10)
        String  contactEmail;
        String  contactPhone;

        // DSA: ArrayList stores 3 months of emission history for predictions
        ArrayList<Double> emissionHistory = new ArrayList<>();

        // DSA: ArrayList stores this company's own transaction IDs (for "My Trade History")
        ArrayList<Integer> myTxIds = new ArrayList<>();

        // Notification inbox: simple ArrayList of strings
        ArrayList<String> notifications = new ArrayList<>();

        // Whether this company has been flagged by fraud detection
        boolean isFlagged;
        String  flagReason;

        // ---- Constructor -------------------------------------------------------
        Company(String id, String name, String rawPassword, Domain domain, int employees) {
            this.companyId       = id;
            this.name            = name;
            this.domain          = domain;
            this.employees       = employees;
            this.salt            = generateSalt();
            this.passwordHash    = sha256(this.salt + rawPassword);

            // Core formula (unchanged from v2.0)
            this.emissionLimit   = employees * domain.multiplier * 10;

            // Default: 75% emission usage at registration
            this.currentEmission = emissionLimit * 0.75;

            // Credits auto-calculated: capacity below limit becomes credits
            // If emission < limit  -> surplus capacity = (limit - emission) = credits
            // Buffer: start with 10% over-limit as buffer allotment
            this.credits         = (emissionLimit - currentEmission) + (emissionLimit * 0.10);

            this.requestCount    = 0;
            this.isBlocked       = false;
            this.isFlagged       = false;
            this.flagReason      = "";
            this.contactEmail    = "";
            this.contactPhone    = "";

            // Seed 3 months of emission history (for prediction engine)
            emissionHistory.add(currentEmission * 0.88);
            emissionHistory.add(currentEmission * 0.94);
            emissionHistory.add(currentEmission);
        }

        // Max-Heap: company with MORE credits = higher priority seller
        @Override
        public int compareTo(Company other) {
            return Double.compare(other.credits, this.credits); // descending
        }

        // Credits safely available to sell (must keep >= 20% as reserve)
        double surplus() {
            return Math.max(0, credits - (credits * 0.20));
        }

        boolean checkPassword(String raw) {
            return passwordHash.equals(sha256(salt + raw));
        }

        // Green score for leaderboard — higher is greener
        // Formula: (credits/limit) - (requests * 0.05) - overLimit penalty
        double greenScore() {
            double overLimit = (currentEmission > emissionLimit)
                    ? (currentEmission - emissionLimit) / emissionLimit : 0;
            return (credits / emissionLimit) - (requestCount * 0.05) - overLimit;
        }

        // 3-month moving average + 6% growth trend = predicted next emission
        double predictNextEmission() {
            if (emissionHistory.isEmpty()) return currentEmission;
            double sum = 0;
            for (double e : emissionHistory) sum += e;
            return (sum / emissionHistory.size()) * 1.06;
        }

        // Status label based on emission vs limit
        String status() {
            if (isBlocked)                               return "BLOCKED";
            if (currentEmission > emissionLimit * 1.5)  return "CRITICAL";
            if (currentEmission > emissionLimit)         return "OVER LIMIT";
            if (currentEmission > emissionLimit * 0.9)  return "WARNING";
            return "COMPLIANT";
        }

       
        void recalculateCredits(double oldEarnedCredits, double newEmission) {
            // Remove old earned portion, add new earned portion
            double oldEarned = Math.max(0, (emissionLimit - currentEmission) * CREDIT_PER_TONNE);
            double newEarned = Math.max(0, (emissionLimit - newEmission) * CREDIT_PER_TONNE);
            // Adjust credits by the difference
            credits = credits - oldEarned + newEarned;
            credits = Math.max(0, credits); // cannot go negative
        }
    }

    // =========================================================================
    //  SECTION 3 — TRANSACTION MODEL (SHA-256 Audit Hash)
    // =========================================================================

    static class Transaction {
        int    txId;
        String sellerId, buyerId;
        double amount, pricePerCredit;
        String timestamp;
        String auditHash;
        String prevHash;
        boolean flagged; // set by fraud detection

        Transaction(int txId, String sellerId, String buyerId,
                    double amount, double price, String prevHash) {
            this.txId           = txId;
            this.sellerId       = sellerId;
            this.buyerId        = buyerId;
            this.amount         = amount;
            this.pricePerCredit = price;
            this.timestamp      = new Date().toString();
            this.prevHash       = prevHash;
            this.flagged        = false;
            // Build the hash input string and hash it
            String raw = txId + sellerId + buyerId + amount + price + timestamp + prevHash;
            this.auditHash = sha256(raw);
        }
    }

    // =========================================================================
    //  SECTION 4 — IN-MEMORY DSA STORAGE
    // =========================================================================

    // --- Company storage ---
    // HashMap: O(1) lookup by companyId
    static HashMap<String, Company>  companyMap  = new HashMap<>();
    // ArrayList: ordered list for iteration, display, leaderboard sorting
    static ArrayList<Company>        companyList = new ArrayList<>();
    // HashMap: O(1) check — is this name already taken?
    static HashMap<String, Boolean>  nameIndex   = new HashMap<>();

    // --- Transaction storage ---
    // ArrayList: append-only audit trail (each record SHA-256 hashed)
    static ArrayList<Transaction>    ledger      = new ArrayList<>();

   
   
    static HashMap<String, ArrayList<String>> tradeGraph = new HashMap<>();

    // --- Fraud Detection ---
    // HashSet: O(1) lookup to check if a company is flagged
    static HashSet<String>           flaggedSet  = new HashSet<>();

    // --- Counters ---
    static int     nextCompanyNum = 1;
    static int     nextTxId       = 1;

    // --- Session state ---
    static Company currentUser    = null;  // logged-in company (null = nobody)
    static boolean adminLoggedIn  = false; // true if admin session active

    // =========================================================================
    //  SECTION 5 — SECURITY UTILITIES (unchanged from v2.0)
    // =========================================================================

    // Compute SHA-256 of any string -> 64-character hex string
    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "HASH_ERROR";
        }
    }

    // Random 8-char salt for password hashing
    static String generateSalt() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random rng = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    // Return the hash of the last ledger entry (chains transactions together)
    static String lastHash() {
        if (ledger.isEmpty()) return "GENESIS";
        return ledger.get(ledger.size() - 1).auditHash;
    }

    // =========================================================================
    //  SECTION 6 — DYNAMIC PRICING (unchanged from v2.0)
    // =========================================================================

    
    static double dynamicPrice(double demand, double supply) {
        if (supply <= 0) return MAX_PRICE;
        double price = BASE_CREDIT_PRICE * (demand / supply);
        return Math.max(MIN_PRICE, Math.min(MAX_PRICE, price));
    }

    // =========================================================================
    //  SECTION 7 — VALIDATION PIPELINE (5 stages, unchanged from v2.0)
    // =========================================================================

    static String validate(Company buyer, Company seller, double requested) {
        if (buyer.isBlocked)
            return "Stage 1 FAIL: Your account is BLOCKED.";
        if (buyer.requestCount >= MAX_REQUESTS)
            return "Stage 2 FAIL: Max " + MAX_REQUESTS + " buy requests per cycle reached.";
        if (buyer.currentEmission > buyer.emissionLimit * 1.5)
            return "Stage 3 FAIL: Your emission exceeds 1.5x your limit. Trade rejected.";
        if (requested > buyer.emissionLimit * 0.50)
            return "Stage 4 FAIL: Cannot buy more than 50% of your emission limit at once.";
        if (seller.credits - requested < seller.credits * 0.20)
            return "Stage 5 FAIL: Seller must keep >=20% reserve. Max they can sell: "
                    + String.format("%.2f", seller.surplus());
        if (seller.credits < requested)
            return "Stage 5 FAIL: Seller only has " + String.format("%.2f", seller.credits)
                    + " credits.";
        if (buyer.companyId.equals(seller.companyId))
            return "Cannot trade with yourself!";
        return null; // all 5 stages passed
    }

    // =========================================================================
    //  SECTION 8 — MATCHING ENGINE (Max-Heap PriorityQueue, unchanged from v2.0)
    // =========================================================================

 
    static Company findBestSeller(Company buyer, double needed) {
        // Build max-heap of all eligible sellers
        PriorityQueue<Company> heap = new PriorityQueue<>();
        for (Company c : companyList) {
            if (!c.companyId.equals(buyer.companyId) && !c.isBlocked && c.surplus() >= needed) {
                heap.add(c); // O(log n)
            }
        }
        if (heap.isEmpty()) return null;

        // Pass 1: prefer same-domain seller
        List<Company> skipped = new ArrayList<>();
        while (!heap.isEmpty()) {
            Company top = heap.poll(); // O(log n) — always removes richest
            if (top.domain == buyer.domain) return top;
            skipped.add(top);
        }

        // Pass 2: no same-domain match -> richest available
        return skipped.isEmpty() ? null : skipped.get(0);
    }

    // =========================================================================
    //  SECTION 9 — PREDICTIVE WARNING ENGINE
    // =========================================================================

   
    static void checkPredictiveWarning(Company c) {
        double predicted = c.predictNextEmission();
        if (predicted > c.emissionLimit) {
            double excess = predicted - c.emissionLimit;
            int creditsNeeded = (int) Math.ceil(excess / 10.0);
            System.out.println();
            System.out.println("  +------------------------------------------------------+");
            System.out.println("  |  ** PREDICTIVE WARNING — AI EMISSION FORECAST **    |");
            System.out.println("  +------------------------------------------------------+");
            System.out.printf ("  |  3-month avg            : %8.1f t               |%n", c.currentEmission);
            System.out.printf ("  |  Predicted next month   : %8.1f t (6%% growth)   |%n", predicted);
            System.out.printf ("  |  Your emission limit    : %8.1f t               |%n", c.emissionLimit);
            System.out.printf ("  |  Expected overshoot     : %8.1f t               |%n", excess);
            System.out.printf ("  |  Recommended credits    : buy %4d NOW            |%n", creditsNeeded);
            System.out.println("  +------------------------------------------------------+");
        }
    }

    // =========================================================================
    //  SECTION 10 — GRAPH: TRADE NETWORK HELPERS
    // =========================================================================

  
    static void addTradeEdge(String sellerId, String buyerId) {
        // Add buyer to seller's adjacency list
        tradeGraph.computeIfAbsent(sellerId, k -> new ArrayList<>()).add(buyerId);
        // Add seller to buyer's adjacency list
        tradeGraph.computeIfAbsent(buyerId, k -> new ArrayList<>()).add(sellerId);
    }

    // Count how many unique partners a company has traded with
    static int tradePartnerCount(String companyId) {
        ArrayList<String> partners = tradeGraph.get(companyId);
        if (partners == null) return 0;
        // Use a HashSet to count unique partners
        return new HashSet<>(partners).size();
    }

    // =========================================================================
    //  SECTION 11 — FRAUD DETECTION 
    // =========================================================================

    static void runFraudDetection(Company buyer, double amount) {
        boolean flagged = false;
        String reason = "";

        // Rule 1: Buying when far below emission limit (< 40% of limit)
        if (buyer.currentEmission < buyer.emissionLimit * 0.40) {
            flagged = true;
            reason = "Rule 1: Bought credits while emission only "
                    + String.format("%.0f", (buyer.currentEmission / buyer.emissionLimit) * 100)
                    + "% of limit --possible hoarding.";
        }

        // Rule 3: Very large single trade
        if (amount > buyer.emissionLimit * 0.70) {
            flagged = true;
            reason += (reason.isEmpty() ? "" : " | ") +
                    "Rule 3: Single trade " + String.format("%.0f", amount)
                    + " credits > 70% of emission limit.";
        }

        if (flagged) {
            buyer.isFlagged   = true;
            buyer.flagReason  = reason;
            flaggedSet.add(buyer.companyId); // DSA: HashSet O(1) insert
            buyer.notifications.add("[SUSPICIOUS ACTIVITY FLAG] System flagged your account: " + reason);
            warn("REVIEW FLAG on " + buyer.name + ": " + reason);
        }
    }

    // =========================================================================
    //  SECTION 12 — PENALTY SYSTEM 
    // =========================================================================

    // Penalty system: WARNING -> FINE -> BLOCK based on buy count and emissions
    static void applyPenalties(Company c) {
        if (c.currentEmission > c.emissionLimit * 2.0) {
            c.isBlocked = true;
            flaggedSet.add(c.companyId);
            warn("PENALTY LV3 - ACCOUNT BLOCKED: Emission > 2x limit.");
            c.notifications.add("[BLOCKED] Account auto-blocked: emission exceeded 2x limit.");
        } else if (c.requestCount >= 3) {
            double fine = (c.requestCount - 2) * 10.0;
            c.credits = Math.max(0, c.credits - fine);
            warn("PENALTY LV2 - FINE: " + fine + " credits deducted for excessive buying.");
            c.notifications.add("[FINE] " + fine + " credits deducted — excessive buy requests.");
        } else if (c.requestCount >= 2) {
            warn("PENALTY LV1 - WARNING: " + c.requestCount + " buy requests used. Max is " + MAX_REQUESTS + ".");
            c.notifications.add("[WARNING] " + c.requestCount + " buy requests used this cycle.");
        }
    }

    // =========================================================================
    //  SECTION 13 — EXECUTE TRADE (shared by manual & auto-match)
    // =========================================================================

    
    static void executeTrade(Company buyer, Company seller, double amount) {
        // 5-stage validation
        String blocked = validate(buyer, seller, amount);
        if (blocked != null) { err(blocked); return; }

        // Dynamic pricing (unchanged from v2.0)
        double price = dynamicPrice(amount, seller.surplus());

        // Mutate credits on the shared Company objects
        seller.credits -= amount;
        buyer.credits  += amount;
        buyer.requestCount++;

        // Record transaction IDs in each company's own history list
        buyer.myTxIds.add(nextTxId);
        seller.myTxIds.add(nextTxId);

        // Apply penalties (unchanged from v2.0)
        applyPenalties(buyer);

        // Create SHA-256-hashed transaction; append to ledger — O(1)
        Transaction tx = new Transaction(nextTxId++, seller.companyId,
                buyer.companyId, amount, price, lastHash());
        ledger.add(tx);

        // Update trade network graph — O(1)
        addTradeEdge(seller.companyId, buyer.companyId);

        // Notify both parties
        buyer.notifications.add("[TRADE] Bought " + String.format("%.2f", amount)
                + " credits from " + seller.name + " @ Rs." + String.format("%.2f", price) + "/credit.");
        seller.notifications.add("[TRADE] Sold " + String.format("%.2f", amount)
                + " credits to " + buyer.name + " @ Rs." + String.format("%.2f", price) + "/credit.");

        // Run fraud detection after trade
        runFraudDetection(buyer, amount);

        System.out.println();
        ok("TRADE EXECUTED SUCCESSFULLY!");
        System.out.println("  +------------------------------------------------------+");
        System.out.printf ("  |  TX ID        : %-35s|%n", "TX-" + String.format("%04d", tx.txId));
        System.out.printf ("  |  Seller       : %-35s|%n", seller.name);
        System.out.printf ("  |  Buyer        : %-35s|%n", buyer.name);
        System.out.printf ("  |  Credits      : %-35s|%n", String.format("%.2f", amount));
        System.out.printf ("  |  Price/credit : Rs. %-31s|%n", String.format("%.2f", price));
        System.out.printf ("  |  Total Value  : Rs. %-31s|%n", String.format("%.2f", amount * price));
        System.out.printf ("  |  Audit Hash   : %-35s|%n", tx.auditHash.substring(0, 20) + "...");
        System.out.println("  +------------------------------------------------------+");
        line();
    }

    // =========================================================================
    //  SECTION 14 — CONSOLE UI HELPERS
    // =========================================================================

    static void printBanner() {
        System.out.println();
        System.out.println("  +============================================================+");
        System.out.println("  |    *** CARBON CREDIT TRADING SYSTEM ***              |");
        System.out.println("  +============================================================+");
        System.out.println();
    }

    // START MENU: shown when nobody is logged in
    static void printStartMenu() {
        System.out.println("  +--------------------------------------+");
        System.out.println("  |         MAIN MENU                    |");
        System.out.println("  +--------------------------------------+");
        System.out.println("  |  1  Register New Company             |");
        System.out.println("  |  2  Company Login                    |");
        System.out.println("  |  3  Admin Login                      |");
        System.out.println("  |  0  Exit                             |");
        System.out.println("  +--------------------------------------+");
        System.out.print("  >> Choice: ");
    }

    // COMPANY MENU: shown after company login
    static void printCompanyMenu(String label) {
        System.out.println();
        System.out.println("  +====================================================+");
        System.out.printf ("  |  Company: %-41s|%n", label);
        System.out.println("  +====================================================+");
        System.out.println("  |  1   My Dashboard                                  |");
        System.out.println("  |  2   Update Monthly Emission                       |");
        System.out.println("  |  3   View Marketplace                              |");
        System.out.println("  |  4   Trade Credits  (manual)                       |");
        System.out.println("  |  5   Auto-Match Trade                              |");
        System.out.println("  |  6   Search Company                                |");
        System.out.println("  |  7   My Trade History                              |");
        System.out.println("  |  8   Green Leaderboard                             |");
        System.out.println("  |  9   Notifications                                 |");
        System.out.println("  |  10  Profile Management                            |");
        System.out.println("  |  L   Logout                                        |");
        System.out.println("  |  0   Exit                                          |");
        System.out.println("  +====================================================+");
        System.out.print("  >> Choice: ");
    }

    // ADMIN MENU: shown after admin login
    static void printAdminMenu() {
        System.out.println();
        System.out.println("  +====================================================+");
        System.out.println("  |  ADMIN PANEL                                       |");
        System.out.println("  +====================================================+");
        System.out.println("  |  1   View All Companies                            |");
        System.out.println("  |  2   Audit Ledger  (SHA-256 chain)                 |");
        System.out.println("  |  3   Verify Ledger Integrity                       |");
        System.out.println("  |  4   Fraud Detection Center                        |");
        System.out.println("  |  5   Block Company                                 |");
        System.out.println("  |  6   Unblock Company                               |");
        System.out.println("  |  7   Trade Network Analysis                        |");
        System.out.println("  |  8   Market Analytics                              |");
        System.out.println("  |  9  Green Leaderboard                              |");
        System.out.println("  |  10  System Reports                                |");
        System.out.println("  |  11  View Flagged Companies                        |");
        System.out.println("  |  L   Logout                                        |");
        System.out.println("  |  0   Exit                                          |");
        System.out.println("  +====================================================+");
        System.out.print("  >> Choice: ");
    }

    static void line()        { System.out.println("  " + "-".repeat(60)); }
    static void ok(String m)  { System.out.println("  [OK]   " + m); }
    static void err(String m) { System.out.println("  [ERR]  " + m); }
    static void info(String m){ System.out.println("  [INFO] " + m); }
    static void warn(String m){ System.out.println("  [WARN] " + m); }

    // =========================================================================
    //  SECTION 15 — REGISTER COMPANY 
    // =========================================================================

    static void registerCompany(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  REGISTER NEW COMPANY");
        line();

        try {
            System.out.print("  Company Name       : ");
            String name = sc.nextLine().trim();
            if (name.isEmpty()) { err("Name cannot be empty."); return; }

            // DSA: HashMap O(1) check — prevent duplicate names
            if (nameIndex.containsKey(name.toLowerCase())) {
                err("\"" + name + "\" already exists. All company names must be unique.");
                return;
            }

            // Show domain choices
            System.out.println();
            System.out.println("  +-- Choose Domain -------------------------------------------------------+");
            Domain[] domains = Domain.values();
            for (int i = 0; i < domains.length; i++) {
                System.out.printf("  |  %d  %-14s  x%.1f  %s%n",
                        i+1, domains[i].name(), domains[i].multiplier, domains[i].description);
            }
            System.out.println("  +------------------------------------------------------------------------+");
            System.out.print("  Select (1-" + domains.length + ")    : ");

            Domain domain;
            try {
                int ch = Integer.parseInt(sc.nextLine().trim()) - 1;
                if (ch < 0 || ch >= domains.length) { err("Invalid choice."); return; }
                domain = domains[ch];
            } catch (NumberFormatException e) { err("Enter a number for domain."); return; }

            System.out.print("  Number of Employees: ");
            int employees;
            try { employees = Integer.parseInt(sc.nextLine().trim()); }
            catch (NumberFormatException e) { err("Invalid number for employees."); return; }
            if (employees <= 0) { err("Employees must be > 0."); return; }

            System.out.print("  Set Password       : ");
            String password = sc.nextLine().trim();
            if (password.length() < 4) { err("Password must be at least 4 characters."); return; }

            System.out.print("  Email (optional)   : ");
            String email = sc.nextLine().trim();

            System.out.print("  Phone (optional)   : ");
            String phone = sc.nextLine().trim();

            // Auto-generate unique company ID: C001, C002, ...
            String cid = String.format("C%03d", nextCompanyNum++);
            while (companyMap.containsKey(cid)) cid = String.format("C%03d", nextCompanyNum++);

            Company c = new Company(cid, name, password, domain, employees);
            c.contactEmail = email;
            c.contactPhone = phone;

            // DSA: Insert into HashMap O(1) and ArrayList O(1) amortised
            companyMap.put(cid, c);
            companyList.add(c);
            nameIndex.put(name.toLowerCase(), true);

           
            System.out.println();
            ok("Company registered and APPROVED!");
            System.out.println("  +---------------------------------------------------+");
            System.out.printf ("  |  Your Company ID  : %-29s|%n", cid);
            System.out.printf ("  |  Domain           : %-29s|%n", domain.name());
            System.out.printf ("  |  Emission Formula : %d emp x %.1f x 10 = %.0f t%n",
                    employees, domain.multiplier, c.emissionLimit);
            System.out.printf ("  |  Credits Allotted : %-29s|%n",
                    String.format("%.2f               ", c.credits));
            System.out.println("  +---------------------------------------------------+");
            info("SAVE your ID: [" + cid + "] .you need it to login!");
            line();

        } catch (Exception e) {
            err("Unexpected error during registration: " + e.getMessage());
        }
    }

    // =========================================================================
    //  SECTION 16 — COMPANY LOGIN
    // =========================================================================

    static boolean companyLogin(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  COMPANY LOGIN");
        line();

        try {
            System.out.print("  Company ID : ");
            String id = sc.nextLine().trim().toUpperCase();

            // DSA: HashMap O(1) lookup
            Company c = companyMap.get(id);
            if (c == null) { err("No company found with ID: " + id); return false; }

            System.out.print("  Password   : ");
            String pwd = sc.nextLine().trim();

            if (!c.checkPassword(pwd)) { err("Incorrect password."); return false; }
            if (c.isBlocked)           { err("Account is BLOCKED. Contact admin."); return false; }

            currentUser    = c;
            adminLoggedIn  = false;
            System.out.println();
            ok("Welcome back, " + c.name + "! Login successful.");
            line();
            return true;

        } catch (Exception e) {
            err("Login error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    //  SECTION 17 — ADMIN LOGIN
    // =========================================================================

    static boolean adminLogin(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  ADMIN LOGIN");
        line();

        try {
            System.out.print("  Admin ID   : ");
            String id  = sc.nextLine().trim();
            System.out.print("  Password   : ");
            String pwd = sc.nextLine().trim();

            if (id.equals(ADMIN_ID) && pwd.equals(ADMIN_PASSWORD)) {
                adminLoggedIn = true;
                currentUser   = null;
                ok("Admin login successful. Welcome!");
                line();
                return true;
            } else {
                err("Invalid admin credentials.");
                return false;
            }
        } catch (Exception e) {
            err("Admin login error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    //  SECTION 18 — COMPANY FEATURE: DASHBOARD
    // =========================================================================

    static void showDashboard() {
        Company c = currentUser;
        System.out.println();
        System.out.println("  +============================================================+");
        System.out.printf ("  |  DASHBOARD --- %-47s|%n", c.name);
        System.out.println("  +============================================================+");
        System.out.printf ("  |  Company ID       : %-40s|%n", c.companyId);
        System.out.printf ("  |  Domain           : %-40s|%n", c.domain.name());
        System.out.printf ("  |  Employees        : %-40s|%n", c.employees);
        System.out.println("  +------------------------------------------------------------+");
        System.out.printf ("  |  Credits Held     : %-40s|%n", String.format("%.2f", c.credits));
        System.out.printf ("  |  Surplus (sellable): %-39s|%n", String.format("%.2f", c.surplus()));
        System.out.printf ("  |  Buy Requests     : %-40s|%n",
                c.requestCount + " / " + MAX_REQUESTS + " used this cycle");
        System.out.println("  +------------------------------------------------------------+");
        System.out.printf ("  |  Emission Limit   : %-40s|%n", String.format("%.1f t", c.emissionLimit));
        System.out.printf ("  |  Current Emission : %-40s|%n", String.format("%.1f t", c.currentEmission));
        System.out.printf ("  |  Status           : %-40s|%n", c.status());
        System.out.printf ("  |  Green Score      : %-40s|%n", String.format("%.3f", c.greenScore()));
        System.out.printf ("  |  Fraud Flag       : %-40s|%n", c.isFlagged ? "YES - " + c.flagReason : "None");
        System.out.println("  +============================================================+");

        // Predictive warning (from v2.0)
        checkPredictiveWarning(c);
        System.out.println();
    }

    // =========================================================================
    //  SECTION 19 — COMPANY FEATURE: UPDATE MONTHLY EMISSION
    // =========================================================================

   
    static void updateMonthlyEmission(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  UPDATE MONTHLY EMISSION DATA");
        System.out.println("  Credits will be recalculated automatically.");
        line();

        try {
            System.out.print("  Electricity used this month (kWh)   : ");
            double electricity = Double.parseDouble(sc.nextLine().trim());
            if (electricity < 0) { err("Electricity cannot be negative."); return; }

            System.out.print("  Fuel used this month (litres)       : ");
            double fuel = Double.parseDouble(sc.nextLine().trim());
            if (fuel < 0) { err("Fuel cannot be negative."); return; }

            System.out.print("  Production volume this month (units) : ");
            double production = Double.parseDouble(sc.nextLine().trim());
            if (production < 0) { err("Production cannot be negative."); return; }

            Company c = currentUser;

            // Calculate new emission in tonnes CO2
            double emissionKg = (electricity * 0.82)
                    + (fuel        * 2.68)
                    + (production  * c.domain.multiplier * 5.0);
            double newEmission = emissionKg / 1000.0;

            double oldEmission = c.currentEmission;

            // Recalculate credits based on new emission
            c.recalculateCredits(oldEmission, newEmission);
            c.currentEmission = newEmission;

            // DSA: append to emission history ArrayList (O(1))
            c.emissionHistory.add(newEmission);
            // Keep only last 3 months
            if (c.emissionHistory.size() > 3) {
                c.emissionHistory.remove(0);
            }

            // Auto-block if emission > 2x limit (from penalty system v2.0)
            applyPenalties(c);

            System.out.println();
            ok("Monthly emission updated!");
            System.out.println("  +------------------------------------------------------+");
            System.out.printf ("  |  Previous Emission : %-31s|%n", String.format("%.2f t", oldEmission));
            System.out.printf ("  |  New Emission      : %-31s|%n", String.format("%.2f t", newEmission));
            System.out.printf ("  |  Emission Limit    : %-31s|%n", String.format("%.2f t", c.emissionLimit));
            System.out.printf ("  |  Credits Now       : %-31s|%n", String.format("%.2f", c.credits));
            System.out.printf ("  |  Status            : %-31s|%n", c.status());
            System.out.println("  +------------------------------------------------------+");

            if (newEmission > c.emissionLimit) {
                double deficit = newEmission - c.emissionLimit;
                warn("You are " + String.format("%.2f", deficit) + " t OVER your limit.");
                warn("Consider buying " + (int)Math.ceil(deficit) + " credits from the marketplace.");
            } else {
                double surplus = c.emissionLimit - newEmission;
                ok("You are " + String.format("%.2f", surplus) + " t UNDER your limit.");
                ok("Bonus credits earned for staying green!");
            }

            checkPredictiveWarning(c);
            line();

        } catch (NumberFormatException e) {
            err("Invalid input — please enter numeric values for emission data.");
        } catch (Exception e) {
            err("Error updating emission: " + e.getMessage());
        }
    }

    // =========================================================================
    //  SECTION 20 — COMPANY FEATURE: VIEW MARKETPLACE
    // =========================================================================

  
    static void viewMarketplace() {
        System.out.println();
        line();
        System.out.println("  MARKETPLACE -- Companies with credits available to sell");
        line();

        System.out.printf("  %-5s  %-18s  %-13s  %10s  %10s%n",
                "ID", "Name", "Domain", "Surplus", "Est.Price");
        System.out.println("  " + "-".repeat(62));

        int count = 0;
        for (Company c : companyList) {
            // Only show non-blocked companies with surplus > 0
            if (!c.isBlocked && c.surplus() > 0
                    && !c.companyId.equals(currentUser.companyId)) {
                double estPrice = dynamicPrice(100, c.surplus()); // estimate for 100 credits
                System.out.printf("  %-5s  %-18s  %-13s  %10.2f  Rs.%7.2f%n",
                        c.companyId, c.name, c.domain.name(), c.surplus(), estPrice);
                count++;
            }
        }

        if (count == 0) {
            info("No sellers available at this time.");
        }
        line();
    }

    // =========================================================================
    //  SECTION 21 — COMPANY FEATURE: MANUAL TRADE
    // =========================================================================

    static void manualTrade(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  TRADE CREDITS -- MANUAL  (you choose the seller)");
        line();

        try {
            Company buyer = currentUser;
            System.out.print("  Seller Company ID : ");
            String sid = sc.nextLine().trim().toUpperCase();

            Company seller = companyMap.get(sid); // O(1) HashMap lookup
            if (seller == null) { err("Seller ID not found."); return; }
            if (seller.companyId.equals(currentUser.companyId)) {
               err("Self-trading is not allowed. Please choose another company.");
               return;
            }

            System.out.printf("  Seller: %s  |  Available: %.2f credits%n",
                    seller.name, seller.surplus());
            System.out.print("  Credits to buy    : ");
            double amount = Double.parseDouble(sc.nextLine().trim());
            if (amount <= 0) { err("Amount must be positive."); return; }

            executeTrade(buyer, seller, amount);

        } catch (NumberFormatException e) {
            err("Invalid trade amount — enter a number.");
        } catch (Exception e) {
            err("Trade error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  SECTION 22 — COMPANY FEATURE: AUTO-MATCH TRADE
    // =========================================================================

    static void autoMatchTrade(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  AUTO-MATCH TRADE  ");
        line();

        try {
            Company buyer = currentUser;
            System.out.print("  Credits you want  : ");
            double amount = Double.parseDouble(sc.nextLine().trim());
            if (amount <= 0) { err("Amount must be positive."); return; }

            info("Priority: same-domain seller -> richest available seller");

            Company seller = findBestSeller(buyer, amount);
            if (seller == null) {
                err("No eligible seller found with at least " + amount + " credits.");
                return;
            }

            boolean sameDomain = seller.domain == buyer.domain;
            ok("Best match: " + seller.name + " (" + seller.companyId + ")"
                    + (sameDomain ? "  [SAME INDUSTRY]" : "  [cross-INDUSTRY]"));

            executeTrade(buyer, seller, amount);

        } catch (NumberFormatException e) {
            err("Invalid amount — enter a number.");
        } catch (Exception e) {
            err("Auto-match error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  SECTION 23 — COMPANY FEATURE: SEARCH COMPANY
    // =========================================================================

    static void searchById(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  SEARCH COMPANY BY ID");
        line();

        try {
            System.out.print("  Enter Company ID: ");
            String id = sc.nextLine().trim().toUpperCase();

            // DSA: HashMap O(1) lookup
            Company c = companyMap.get(id);
            if (c == null) { err("No company found with ID: " + id); return; }

            System.out.println();
            System.out.println("  +---------------------------------------------------+");
            System.out.printf ("  |  ID              : %-30s|%n", c.companyId);
            System.out.printf ("  |  Name            : %-30s|%n", c.name);
            System.out.printf ("  |  Domain          : %-30s|%n", c.domain.name());
            System.out.printf ("  |  Employees       : %-30s|%n", c.employees);
            System.out.printf ("  |  Credits         : %-30s|%n", String.format("%.2f", c.credits));
            System.out.printf ("  |  Surplus         : %-30s|%n", String.format("%.2f (can sell)", c.surplus()));
            System.out.printf ("  |  Emission        : %-30s|%n",
                    String.format("%.1f t / %.1f t", c.currentEmission, c.emissionLimit));
            System.out.printf ("  |  Status          : %-30s|%n", c.status());
            System.out.printf ("  |  Green Score     : %-30s|%n", String.format("%.3f", c.greenScore()));
            System.out.printf ("  |  Trade Partners  : %-30s|%n", tradePartnerCount(c.companyId));
            System.out.println("  +---------------------------------------------------+");
            line();

        } catch (Exception e) {
            err("Search error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  SECTION 24 — COMPANY FEATURE: MY TRADE HISTORY
    // =========================================================================

   
    static void myTradeHistory() {
        System.out.println();
        line();
        System.out.printf("   TRADE HISTORY -- %s%n", currentUser.name);
        line();

        if (currentUser.myTxIds.isEmpty()) {
            info("No trades yet.");
            return;
        }

        System.out.printf("  %-7s  %-6s  %-6s  %9s  %10s%n",
                "TX-ID", "Seller", "Buyer", "Credits", "Price/unit");
        System.out.println("  " + "-".repeat(48));

        // DSA: iterate the company's own txId ArrayList, find in ledger
        for (int txId : currentUser.myTxIds) {
            for (Transaction t : ledger) {         // O(n) scan
                if (t.txId == txId) {
                    String role = t.buyerId.equals(currentUser.companyId) ? "BOUGHT" : "SOLD";
                    System.out.printf("  TX-%04d  %-6s  %-6s  %9.2f  Rs.%-7.2f  [%s]%n",
                            t.txId, t.sellerId, t.buyerId,
                            t.amount, t.pricePerCredit, role);
                    break;
                }
            }
        }
        line();
    }

    // =========================================================================
    //  SECTION 25 — COMPANY FEATURE: GREEN LEADERBOARD
    // =========================================================================

   
    static void showLeaderboard() {
        System.out.println();
        line();
        System.out.println("  GREEN LEADERBOARD");
        System.out.println("  Score = (credits/limit) - (requests x 0.05) - overLimit penalty");
        line();

        if (companyList.isEmpty()) { info("No companies to rank."); return; }

        // DSA: copy ArrayList then sort — O(n log n)
        List<Company> ranked = new ArrayList<>(companyList);
        ranked.sort((a, b) -> Double.compare(b.greenScore(), a.greenScore()));

        System.out.printf("  %-6s  %-18s  %-13s  %9s  %9s  %8s%n",
                "Rank", "Company", "Domain", "Credits", "Status", "Score");
        System.out.println("  " + "-".repeat(70));

        int rank = 1;
        for (Company c : ranked) {
            String medal = rank == 1 ? "[1st]" : rank == 2 ? "[2nd]" : rank == 3 ? "[3rd]" : "    ";
            String you   = (currentUser != null && c.companyId.equals(currentUser.companyId))
                    ? " <- YOU" : "";
            System.out.printf("  %s %-18s  %-13s  %9.2f  %9s  %8.3f%s%n",
                    medal, c.name, c.domain.name(),
                    c.credits, c.status(), c.greenScore(), you);
            rank++;
        }
        line();
    }

    // =========================================================================
    //  SECTION 26 — COMPANY FEATURE: NOTIFICATIONS
    // =========================================================================

    static void showNotifications() {
        System.out.println();
        line();
        System.out.printf("  NOTIFICATIONS — %s%n", currentUser.name);
        line();

        ArrayList<String> notifs = currentUser.notifications;
        if (notifs.isEmpty()) {
            info("No notifications yet.");
        } else {
            // DSA: iterate ArrayList O(n)
            for (int i = notifs.size() - 1; i >= 0; i--) { // newest first
                System.out.println("  > " + notifs.get(i));
            }
        }
        line();
    }

    // =========================================================================
    //  SECTION 27 — COMPANY FEATURE: PROFILE MANAGEMENT
    // =========================================================================

    static void profileManagement(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  PROFILE MANAGEMENT");
        line();

        Company c = currentUser;
        System.out.println("  Current Profile:");
        System.out.printf("  Name   : %s%n", c.name);
        System.out.printf("  Email  : %s%n", c.contactEmail.isEmpty() ? "(not set)" : c.contactEmail);
        System.out.printf("  Phone  : %s%n", c.contactPhone.isEmpty() ? "(not set)" : c.contactPhone);
        System.out.println();
        System.out.println("  Update options:");
        System.out.println("  1  Update Email");
        System.out.println("  2  Update Phone");
        System.out.println("  3  Change Password");
        System.out.println("  0  Back");
        System.out.print("  >> Choice: ");

        try {
            String ch = sc.nextLine().trim();
            switch (ch) {
                case "1" -> {
                    System.out.print("  New Email: ");
                    c.contactEmail = sc.nextLine().trim();
                    ok("Email updated.");
                }
                case "2" -> {
                    System.out.print("  New Phone: ");
                    c.contactPhone = sc.nextLine().trim();
                    ok("Phone updated.");
                }
                case "3" -> {
                    System.out.print("  Current Password: ");
                    String old = sc.nextLine().trim();
                    if (!c.checkPassword(old)) { err("Incorrect current password."); return; }
                    System.out.print("  New Password (min 4 chars): ");
                    String newPwd = sc.nextLine().trim();
                    if (newPwd.length() < 4) { err("Too short."); return; }
                    c.salt         = generateSalt();
                    c.passwordHash = sha256(c.salt + newPwd);
                    ok("Password changed successfully.");
                }
                case "0" -> { return; }
                default  -> err("Invalid choice.");
            }
        } catch (Exception e) {
            err("Profile update error: " + e.getMessage());
        }
        line();
    }

    // =========================================================================
    //  SECTION 28 — ADMIN FEATURE: VIEW ALL COMPANIES
    // =========================================================================

    static void adminViewAllCompanies() {
        System.out.println();
        line();
        System.out.printf("  ALL COMPANIES  (%d registered)%n", companyList.size());
        line();

        if (companyList.isEmpty()) { info("No companies yet."); return; }

        System.out.printf("  %-5s  %-18s  %-13s  %9s  %9s  %8s  %7s%n",
                "ID", "Name", "Domain", "Credits", "Status", "Score", "Flagged");
        System.out.println("  " + "-".repeat(78));

        // DSA: O(n) iteration over ArrayList
        for (Company c : companyList) {
            System.out.printf("  %-5s  %-18s  %-13s  %9.2f  %9s  %8.3f  %7s%n",
                    c.companyId, c.name, c.domain.name(),
                    c.credits, c.status(), c.greenScore(),
                    c.isFlagged ? "YES" : "No");
        }
        line();
    }

   

    // =========================================================================
    //  SECTION 30 — ADMIN FEATURE: AUDIT LEDGER (from v2.0)
    // =========================================================================

    static void adminAuditLedger() {
        System.out.println();
        line();
        System.out.printf("  IMMUTABLE AUDIT LEDGER  (%d transactions)%n", ledger.size());// Each record is SHA-256 hashed and chained to the previous.
       
        line();

        if (ledger.isEmpty()) { info("No transactions yet."); return; }

        System.out.printf("  %-7s  %-6s  %-6s  %9s  %10s  %-22s  %s%n",
                "TX-ID", "Seller", "Buyer", "Credits", "Price/unit", "Hash (first 22)", "Flag");
        System.out.println("  " + "-".repeat(78));

        // DSA: O(n) iteration over ArrayList
        for (Transaction t : ledger) {
            System.out.printf("  TX-%04d  %-6s  %-6s  %9.2f  Rs.%-7.2f  %s...  %s%n",
                    t.txId, t.sellerId, t.buyerId,
                    t.amount, t.pricePerCredit,
                    t.auditHash.substring(0, 22),
                    t.flagged ? "[FRAUD]" : "");
        }
        line();
        info("VERIFY: Recompute SHA-256(txId+sellerId+buyerId+amount+price+timestamp+prevHash)");
        info("If any hash mismatches -> ledger has been tampered with.");
        line();
    }

    // =========================================================================
    //  SECTION 31 — ADMIN FEATURE: VERIFY LEDGER INTEGRITY
    // =========================================================================

    /**
     * Walks the ledger and checks each transaction's prevHash matches the
     * previous transaction's auditHash. If any breaks -> tampering detected.
     *
     * DSA: O(n) pass over the ArrayList.
     */
    static void adminVerifyLedger() {
        System.out.println();
        line();
        System.out.println("  VERIFY LEDGER INTEGRITY ");//(SHA-256 Hash Chain)
        line();

        if (ledger.isEmpty()) { info("No transactions to verify."); return; }
        if (ledger.size() == 1) {
            info("Only 1 transaction — checking genesis chain...");
            if (ledger.get(0).prevHash.equals("GENESIS")) {
                ok("Ledger VALID. Genesis chain intact.");
            } else {
                err("TAMPERED! First transaction prevHash is not 'GENESIS'.");
            }
            return;
        }

        boolean intact = true;
        for (int i = 1; i < ledger.size(); i++) {
            String expected = ledger.get(i - 1).auditHash;
            String actual   = ledger.get(i).prevHash;
            if (!expected.equals(actual)) {
                err("TAMPERED! TX-" + String.format("%04d", ledger.get(i).txId)
                        + " prevHash does not match TX-"
                        + String.format("%04d", ledger.get(i-1).txId) + " auditHash.");
                intact = false;
            }
        }

        if (intact) {
            ok("Ledger INTEGRITY VERIFIED. All " + ledger.size() + " transactions intact.");
            ok("Hash chain is unbroken from GENESIS to TX-" + String.format("%04d", ledger.get(ledger.size()-1).txId));
        } else {
            err("Ledger has been TAMPERED. Investigate immediately.");
        }
        line();
    }

    // =========================================================================
    //  SECTION 32 — ADMIN FEATURE: FRAUD DETECTION CENTER
    // =========================================================================

    /**
     * Shows all flagged companies and their reasons.
     * DSA: HashSet<String> flaggedSet — O(1) to check if any company is flagged.
     *       Iterate flaggedSet -> O(f) where f = number of flagged companies.
     */
    static void adminFraudCenter() {
        System.out.println();
        line();
        System.out.printf("  FRAUD DETECTION CENTER  (%d flagged)%n", flaggedSet.size());
        line();

        if (flaggedSet.isEmpty()) {
            ok("No fraud flags. System is clean.");
            return;
        }

        // DSA: iterate HashSet — O(f)
        for (String cid : flaggedSet) {
            Company c = companyMap.get(cid); // O(1)
            if (c != null) {
                System.out.println("  [FLAG] " + c.companyId + " — " + c.name);
                System.out.println("         Reason : " + c.flagReason);
                System.out.println("         Status : " + c.status());
                System.out.println("         Blocked: " + c.isBlocked);
                System.out.println();
            }
        }

        info("Use 'Block Company' (option 6) to block suspicious companies.");
        line();
    }

    // =========================================================================
    //  SECTION 33 — ADMIN FEATURE: BLOCK / UNBLOCK COMPANY
    // =========================================================================

    static void adminBlockCompany(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  BLOCK COMPANY (Admin Manual Action)");
        line();

        try {
            System.out.print("  Enter Company ID to block: ");
            String cid = sc.nextLine().trim().toUpperCase();
            Company c = companyMap.get(cid); // O(1)
            if (c == null) { err("Company not found: " + cid); return; }
            if (c.isBlocked) { info(c.name + " is already blocked."); return; }

            c.isBlocked = true;
            flaggedSet.add(cid);
            c.notifications.add("[ADMIN] Your account has been BLOCKED by admin.");
            ok(c.name + " [" + cid + "] has been BLOCKED.");
            line();

        } catch (Exception e) {
            err("Block error: " + e.getMessage());
        }
    }

    static void adminUnblockCompany(Scanner sc) {
        System.out.println();
        line();
        System.out.println("  UNBLOCK COMPANY (Admin Manual Action)");
        line();

        try {
            System.out.print("  Enter Company ID to unblock: ");
            String cid = sc.nextLine().trim().toUpperCase();
            Company c = companyMap.get(cid); // O(1)
            if (c == null) { err("Company not found: " + cid); return; }
            if (!c.isBlocked) { info(c.name + " is not blocked."); return; }

            c.isBlocked = false;
            c.isFlagged = false;
            c.flagReason = "";
            flaggedSet.remove(cid);
            c.notifications.add("[ADMIN] Your account has been UNBLOCKED by admin.");
            ok(c.name + " [" + cid + "] has been UNBLOCKED.");
            line();

        } catch (Exception e) {
            err("Unblock error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  SECTION 34 — ADMIN FEATURE: TRADE NETWORK ANALYSIS (Graph)
    // =========================================================================

  
    static void adminTradeNetwork() {
        System.out.println();
        line();
        System.out.println("  TRADE NETWORK ANALYSIS (Adjacency List Graph)");
        System.out.println("  Each company -> list of companies it has traded with");
        line();

        if (tradeGraph.isEmpty()) {
            info("No trades have occurred yet — graph is empty.");
            return;
        }

        String mostConnected    = null;
        int    maxPartners      = 0;
        int    totalEdges       = 0;

        for (Map.Entry<String, ArrayList<String>> entry : tradeGraph.entrySet()) {
            String  cid      = entry.getKey();
            Company c        = companyMap.get(cid);
            String  name     = (c != null) ? c.name : cid;
            int     partners = tradePartnerCount(cid); // uses HashSet for unique count

            System.out.printf("  %s (%s)  ->  %d unique partner(s): ",
                    cid, name, partners);
            // Print unique partners
            HashSet<String> seen = new HashSet<>();
            for (String pid : entry.getValue()) {
                if (seen.add(pid)) {
                    Company p = companyMap.get(pid);
                    System.out.print((p != null ? p.name : pid) + "  ");
                }
            }
            System.out.println();

            totalEdges += entry.getValue().size();
            if (partners > maxPartners) {
                maxPartners   = partners;
                mostConnected = name + " (" + cid + ")";
            }
        }

        line();
        info("Total trade connections (directed edges): " + totalEdges);
        info("Most active trader: " + mostConnected + " with " + maxPartners + " partner(s)");
        line();
    }

    // =========================================================================
    //  SECTION 35 — ADMIN FEATURE: MARKET ANALYTICS
    // =========================================================================

    /**
     * Computes market-level stats in O(n) pass over ArrayList and ledger.
     */
    static void adminMarketAnalytics() {
        System.out.println();
        line();
        System.out.println("  MARKET ANALYTICS");
        line();

        if (companyList.isEmpty()) { info("No data yet."); return; }

        // O(n) scan of companies
        double totalCredits = 0, totalEmission = 0, totalLimit = 0;
        int    overLimitCount = 0, blockedCount = 0, flaggedCount = 0;
        for (Company c : companyList) {
            totalCredits  += c.credits;
            totalEmission += c.currentEmission;
            totalLimit    += c.emissionLimit;
            if (c.currentEmission > c.emissionLimit) overLimitCount++;
            if (c.isBlocked) blockedCount++;
            if (c.isFlagged) flaggedCount++;
        }

        // O(n) scan of ledger
        double totalVolume = 0, totalValue = 0;
        double minPrice = Double.MAX_VALUE, maxPrice = Double.MIN_VALUE;
        for (Transaction t : ledger) {
            totalVolume += t.amount;
            totalValue  += t.amount * t.pricePerCredit;
            if (t.pricePerCredit < minPrice) minPrice = t.pricePerCredit;
            if (t.pricePerCredit > maxPrice) maxPrice = t.pricePerCredit;
        }

        System.out.println("  +----------------------------------------------------------+");
        System.out.printf ("  |  Total Companies          : %-29s|%n", companyList.size());
        System.out.printf ("  |  Total Credits in System  : %-29s|%n", String.format("%.2f", totalCredits));
        System.out.printf ("  |  Avg Credits per Company  : %-29s|%n",
                String.format("%.2f", totalCredits / companyList.size()));
        System.out.println("  +----------------------------------------------------------+");
        System.out.printf ("  |  Total Emission (all cos) : %-29s|%n", String.format("%.1f t", totalEmission));
        System.out.printf ("  |  Total Emission Limit     : %-29s|%n", String.format("%.1f t", totalLimit));
        System.out.printf ("  |  System Emission Ratio    : %-29s|%n",
                String.format("%.1f%%", (totalEmission / totalLimit) * 100));
        System.out.printf ("  |  Over-Limit Companies     : %-29s|%n", overLimitCount);
        System.out.printf ("  |  Blocked Companies        : %-29s|%n", blockedCount);
        System.out.printf ("  |  Flagged Companies        : %-29s|%n", flaggedCount);
        System.out.println("  +----------------------------------------------------------+");
        System.out.printf ("  |  Total Transactions       : %-29s|%n", ledger.size());
        System.out.printf ("  |  Total Credits Traded     : %-29s|%n", String.format("%.2f", totalVolume));
        System.out.printf ("  |  Total Market Value       : Rs. %-25s|%n",
                String.format("%.2f", totalValue));
        if (!ledger.isEmpty()) {
            System.out.printf ("  |  Price Range              : Rs.%.2f - Rs.%.2f          |%n",
                    minPrice, maxPrice);
        }
        System.out.println("  +----------------------------------------------------------+");
        line();
    }

    // =========================================================================
    //  SECTION 36 — ADMIN FEATURE: SYSTEM REPORTS
    // =========================================================================

    static void adminSystemReport() {
        System.out.println();
        line();
        System.out.println("  SYSTEM REPORT");
        line();

        System.out.println("  == DSA Structure Status ==");
        System.out.printf("  HashMap (companyMap)     : %d entries%n",   companyMap.size());
        System.out.printf("  ArrayList (companyList)  : %d entries%n",   companyList.size());
        System.out.printf("  HashMap (nameIndex)      : %d entries%n",   nameIndex.size());
        System.out.printf("  ArrayList (ledger)       : %d entries%n",   ledger.size());
        System.out.printf("  Graph (tradeGraph)       : %d nodes%n",     tradeGraph.size());
        System.out.printf("  HashSet (flaggedSet)     : %d flagged%n",   flaggedSet.size());
        System.out.println();
        System.out.println("  == Domain Distribution ==");

        // Count by domain using a HashMap — O(n)
        HashMap<Domain, Integer> domainCount = new HashMap<>();
        for (Company c : companyList) {
            domainCount.put(c.domain, domainCount.getOrDefault(c.domain, 0) + 1);
        }
        for (Map.Entry<Domain, Integer> e : domainCount.entrySet()) {
            System.out.printf("  %-15s : %d companies%n", e.getKey().name(), e.getValue());
        }
        line();
    }

    // =========================================================================
    //  SECTION 37 — ADMIN FEATURE: VIEW FLAGGED COMPANIES
    // =========================================================================

    static void adminViewFlagged() {
        System.out.println();
        line();
        System.out.printf("  FLAGGED COMPANIES (%d)%n", flaggedSet.size());
        line();

        if (flaggedSet.isEmpty()) {
            ok("No companies flagged.");
            return;
        }

        for (String cid : flaggedSet) {
            Company c = companyMap.get(cid);
            if (c != null) {
                System.out.printf("  [%s] %-18s | Blocked: %-5s | Reason: %s%n",
                        c.companyId, c.name, c.isBlocked ? "YES" : "No",
                        c.flagReason.isEmpty() ? "Manually blocked" : c.flagReason);
            }
        }
        line();
    }

    // =========================================================================
    //  SECTION 38 — SAMPLE DATA LOADER
    // =========================================================================

    static void loadSampleData() {
        String[]   names = {"Tata Steel Ltd", "Solar Grid India", "Metro Railways",
                "TechCloud IT",   "AgroFarm Co."};
        String[]   pwds  = {"tata123", "solar1", "metro9", "tech55", "agro77"};
        Domain[]   doms  = {Domain.MANUFACTURING, Domain.ENERGY, Domain.TRANSPORT,
                Domain.IT, Domain.AGRICULTURE};
        int[]      emps  = {5000, 2000, 3500, 1200, 800};

        for (int i = 0; i < names.length; i++) {
            String cid = String.format("C%03d", nextCompanyNum++);
            Company c  = new Company(cid, names[i], pwds[i], doms[i], emps[i]);
            companyMap.put(cid, c);
            companyList.add(c);
            nameIndex.put(names[i].toLowerCase(), true);
        }

        info("5 sample companies loaded: C001 to C005");
        System.out.println("  +-------------------------------------------+");
        System.out.println("  |  ID    Name                Password        |");
        System.out.println("  +-------------------------------------------+");
        System.out.println("  |  C001  Tata Steel Ltd      tata123         |");
        System.out.println("  |  C002  Solar Grid India    solar1          |");
        System.out.println("  |  C003  Metro Railways      metro9          |");
        System.out.println("  |  C004  TechCloud IT        tech55          |");
        System.out.println("  |  C005  AgroFarm Co.        agro77          |");
        System.out.println("  +-------------------------------------------+");
        System.out.println("  |  Admin: ID=admin  Password=admin123        |");
        System.out.println("  +-------------------------------------------+");
        System.out.println();
    }

    // =========================================================================
    //  SECTION 39 — MAIN METHOD
    // =========================================================================

    public static void main(String[] args) {
        Scanner sc      = new Scanner(System.in);
        boolean running = true;

        printBanner();
        loadSampleData();

        while (running) {

            // ---- No one is logged in -> show start menu ----
            if (currentUser == null && !adminLoggedIn) {
                printStartMenu();
                try {
                    String choice = sc.nextLine().trim();
                    switch (choice) {
                        case "1" -> registerCompany(sc);
                        case "2" -> companyLogin(sc);
                        case "3" -> adminLogin(sc);
                        case "0" -> running = false;
                        default  -> err("Enter 1, 2, 3, or 0.");
                    }
                } catch (Exception e) {
                    err("Menu error: " + e.getMessage());
                }

                // ---- Admin session -> show admin menu ----
            } else if (adminLoggedIn) {
                printAdminMenu();
                try {
                    String choice = sc.nextLine().trim().toUpperCase();
                    switch (choice) {
                        case "1"  -> adminViewAllCompanies();
                        case "2"  -> adminAuditLedger();
                        case "3"  -> adminVerifyLedger();
                        case "4"  -> adminFraudCenter();
                        case "5"  -> adminBlockCompany(sc);
                        case "6"  -> adminUnblockCompany(sc);
                        case "7"  -> adminTradeNetwork();
                        case "8"  -> adminMarketAnalytics();
                        case "9" -> showLeaderboard();
                        case "10" -> adminSystemReport();
                        case "11" -> adminViewFlagged();
                        case "L"  -> {
                            ok("Admin logged out.");
                            adminLoggedIn = false;
                        }
                        case "0"  -> running = false;
                        default   -> err("Invalid option. Choose 1-11, L, or 0.");
                    }
                } catch (Exception e) {
                    err("Admin menu error: " + e.getMessage());
                }

                // ---- Company session -> show company menu ----
            } else {
                printCompanyMenu(currentUser.name + " [" + currentUser.companyId + "]");
                try {
                    String choice = sc.nextLine().trim().toUpperCase();
                    switch (choice) {
                        case "1"  -> showDashboard();
                        case "2"  -> updateMonthlyEmission(sc);
                        case "3"  -> viewMarketplace();
                        case "4"  -> manualTrade(sc);
                        case "5"  -> autoMatchTrade(sc);
                        case "6"  -> searchById(sc);
                        case "7"  -> myTradeHistory();
                        case "8"  -> showLeaderboard();
                        case "9"  -> showNotifications();
                        case "10" -> profileManagement(sc);
                        case "L"  -> {
                            ok("Logged out. Goodbye, " + currentUser.name + "!");
                            currentUser = null;
                        }
                        case "0"  -> running = false;
                        default   -> err("Invalid option. Choose 1-10, L, or 0.");
                    }
                } catch (Exception e) {
                    err("Menu error: " + e.getMessage());
                }
            }
        }

        System.out.println();
        System.out.println("  Thank you for trading green. Goodbye!");
        System.out.println();
        sc.close();
    }
}
