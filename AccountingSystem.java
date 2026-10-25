import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;

/**
 * Accounting System - jGRASP / Swing version. Zero external dependencies.
 *
 * Layout matches the original JavaFX spec:
 *   - Dark navy header bar with title + subtitle, three blue buttons (Load / Save / Exit).
 *   - Six tabs in this order: New Transaction, Transactions, Accounts,
 *     General Journal, General Ledger, Balance Sheet.
 *   - Status bar at the bottom (e.g. "Added 1 transaction.").
 *
 * Accounting rules preserved:
 *   - Each transaction has exactly one debit and one credit account, same amount,
 *     debit != credit.
 *   - ASSET / EXPENSE increase with debits; LIABILITY / EQUITY / INCOME with credits.
 *   - Balances are always derived from the transactions, never typed in.
 *   - The Balance Sheet includes a "Net Income" line in equity so it always balances.
 *   - All money is BigDecimal; money formats as #,##0.00.
 *
 * Storage: a plain text file "accounting-data.txt" next to the application.
 *   - On startup it is loaded if present (so data survives restarts).
 *   - On every change it is re-written automatically.
 *   - The "Save" button exports the same pipe-delimited format to a file you choose.
 *   - The "Load" button imports from a file you choose, replacing current data.
 *
 * Default chart of accounts is seeded on first run (same 14 accounts as the
 * JavaFX version).
 *
 * JDK 8+ required. No external jars. Just open in jGRASP, Compile, Run.
 */
public class AccountingSystem extends JFrame {

    // ----------------------------------------------------------------- styling
    private static final Color NAVY     = new Color(0x0d, 0x22, 0x40);
    private static final Color SUBTITLE = new Color(0x9b, 0xb3, 0xd4);
    private static final Color BLUE     = new Color(0x19, 0x76, 0xd2);
    private static final Color BLUE_HOV = new Color(0x11, 0x52, 0x93);
    private static final Color GREEN    = new Color(0x2e, 0x7d, 0x32);
    private static final Color GREEN_BG = new Color(0xe8, 0xf5, 0xe9);
    private static final Color RED      = new Color(0xc6, 0x28, 0x28);
    private static final Color RED_BG   = new Color(0xff, 0xeb, 0xee);
    private static final Color LIGHT_BG = new Color(0xf4, 0xf6, 0xf8);
    private static final Color CARD_BG  = Color.WHITE;
    private static final Color BORDER   = new Color(0xdd, 0xe3, 0xea);
    private static final Color TOTAL_BG = new Color(0xf0, 0xf4, 0xf8);
    private static final Color STATUS_BG = new Color(0xe8, 0xee, 0xf5);

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final String DATA_FILE = "accounting-data.txt";

    // ----------------------------------------------------------------- data
    private final List<Account> accounts = new ArrayList<>();
    private final List<Transaction> transactions = new ArrayList<>();

    // ----------------------------------------------------------------- UI fields
    private JTextField dateField, descField, amountField;
    private JComboBox<Account> debitCombo, creditCombo;
    private JLabel formMsg;
    private JTextField searchField;
    private DefaultTableModel txnModel;
    private JTable txnTable;
    private DefaultTableModel accountsModel;
    private JTable accountsTable;
    private DefaultTableModel journalModel;
    private JTable journalTable;
    private JComboBox<Account> ledgerAccountCombo;
    private DefaultTableModel ledgerModel;
    private JTable ledgerTable;
    private DefaultTableModel assetsModel;
    private JTable assetsTable;
    private DefaultTableModel leModel;
    private JTable leTable;
    private JLabel statusLabel;

    // ================================================================ main
    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) { }
                AccountingSystem app = new AccountingSystem();
                app.setVisible(true);
            }
        });
    }

    public AccountingSystem() {
        super("Accounting System");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmExit(); }
        });
        seedAccounts();
        loadFromDataFile();
        buildUI();
        refreshAll();
        setStatus("Ready.");
    }

    /** Inserts the default chart of accounts (always - accounts themselves are not persisted). */
    private void seedAccounts() {
        String[][] defaults = {
            {"Cash",                "ASSET"},
            {"Accounts Receivable", "ASSET"},
            {"Inventory",           "ASSET"},
            {"Prepaid Expenses",    "ASSET"},
            {"Equipment",           "ASSET"},
            {"Accounts Payable",    "LIABILITY"},
            {"Notes Payable",       "LIABILITY"},
            {"Owner's Capital",     "EQUITY"},
            {"Sales Revenue",       "INCOME"},
            {"Service Revenue",     "INCOME"},
            {"Cost of Goods Sold",  "EXPENSE"},
            {"Rent Expense",        "EXPENSE"},
            {"Salaries Expense",    "EXPENSE"},
            {"Utilities Expense",   "EXPENSE"}
        };
        int id = 1;
        for (String[] a : defaults) {
            accounts.add(new Account(id++, a[0], a[1]));
        }
    }

    // ----------------------------------------------------------------- auto persistence
    private void loadFromDataFile() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length != 5) continue;
                String date = parts[0].trim();
                String desc = unescape(parts[1]);
                String dName = parts[2].trim();
                String cName = parts[3].trim();
                BigDecimal amount;
                try { amount = new BigDecimal(parts[4].trim()); }
                catch (NumberFormatException ex) { continue; }
                Account d = findAccount(dName);
                Account c = findAccount(cName);
                if (d == null || c == null || d == c ||
                    amount.signum() <= 0 || !isValidDate(date)) continue;
                transactions.add(new Transaction(date, desc, d, c, amount));
            }
        } catch (IOException e) {
            // silent - start with empty transactions
        }
    }

    private void saveToDataFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Accounting System data file\n");
        for (Transaction t : transactions) {
            sb.append(t.date).append('|')
              .append(escape(t.description)).append('|')
              .append(t.debit.name).append('|')
              .append(t.credit.name).append('|')
              .append(t.amount.toPlainString()).append('\n');
        }
        try {
            Files.write(Paths.get(DATA_FILE),
                        sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            setStatus("Error auto-saving data file: " + e.getMessage());
        }
    }

    private Account findAccount(String name) {
        for (Account a : accounts) {
            if (a.name.equals(name)) return a;
        }
        return null;
    }

    // ----------------------------------------------------------------- UI build
    private void buildUI() {
        setSize(1180, 800);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(LIGHT_BG);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(LIGHT_BG);
        tabs.addTab("New Transaction",  buildNewTransactionTab());
        tabs.addTab("Transactions",      buildTransactionsTab());
        tabs.addTab("Accounts",          buildAccountsTab());
        tabs.addTab("General Journal",   buildJournalTab());
        tabs.addTab("General Ledger",    buildLedgerTab());
        tabs.addTab("Balance Sheet",    buildBalanceSheetTab());
        add(tabs, BorderLayout.CENTER);

        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(NAVY);
        header.setBorder(new EmptyBorder(14, 24, 14, 24));

        JPanel titleBox = new JPanel(new GridLayout(2, 1, 0, 2));
        titleBox.setOpaque(false);
        JLabel title = new JLabel("Accounting System");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel subtitle = new JLabel(
            "Double-Entry  \u00B7  General Journal  \u00B7  General Ledger  \u00B7  Balance Sheet");
        subtitle.setForeground(SUBTITLE);
        subtitle.setFont(subtitle.getFont().deriveFont(12f));
        titleBox.add(title);
        titleBox.add(subtitle);
        header.add(titleBox, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton load = makeBlueButton("Load");
        JButton save = makeBlueButton("Save");
        JButton exit = makeBlueButton("Exit");
        load.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { doLoad(); }
        });
        save.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { doSave(); }
        });
        exit.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { confirmExit(); }
        });
        buttons.add(load);
        buttons.add(save);
        buttons.add(exit);
        header.add(buttons, BorderLayout.EAST);

        return header;
    }

    private JButton makeBlueButton(String text) {
        final JButton b = new JButton(text);
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setPreferredSize(new Dimension(80, 32));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(BLUE_HOV); }
            @Override public void mouseExited(MouseEvent e)  { b.setBackground(BLUE); }
        });
        return b;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.setBackground(STATUS_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            new EmptyBorder(8, 16, 8, 16)));
        statusLabel = new JLabel("Ready.");
        statusLabel.setForeground(new Color(0x33, 0x33, 0x33));
        bar.add(statusLabel);
        return bar;
    }

    private JPanel card() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CARD_BG);
        p.setBorder(new EmptyBorder(16, 16, 16, 16));
        return p;
    }

    // -------------------------------------------------- Tab 1: New Transaction
    private JComponent buildNewTransactionTab() {
        JPanel card = card();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(CARD_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        dateField   = new JTextField(LocalDate.now().toString(), 20);
        descField   = new JTextField(30);
        debitCombo  = new JComboBox<>();
        creditCombo = new JComboBox<>();
        amountField = new JTextField(15);

        debitCombo.setPreferredSize(new Dimension(280, 26));
        creditCombo.setPreferredSize(new Dimension(280, 26));

        addFieldRow(form, gbc, 0, "Date (YYYY-MM-DD)", dateField);
        addFieldRow(form, gbc, 1, "Description",       descField);
        addFieldRow(form, gbc, 2, "Debit Account",     debitCombo);
        addFieldRow(form, gbc, 3, "Credit Account",    creditCombo);
        addFieldRow(form, gbc, 4, "Amount",            amountField);

        JButton addBtn = makeBlueButton("Add Transaction");
        addBtn.setPreferredSize(new Dimension(Integer.MAX_VALUE, 36));
        addBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        addBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { handleAddTransaction(); }
        });

        formMsg = new JLabel(" ");
        formMsg.setOpaque(true);
        formMsg.setBackground(CARD_BG);
        formMsg.setForeground(new Color(0x33, 0x33, 0x33));
        formMsg.setBorder(new EmptyBorder(8, 12, 8, 12));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(CARD_BG);
        bottom.setBorder(new EmptyBorder(12, 0, 0, 0));
        bottom.add(addBtn, BorderLayout.NORTH);
        bottom.add(Box.createVerticalStrut(10), BorderLayout.CENTER);
        bottom.add(formMsg, BorderLayout.SOUTH);

        card.add(form, BorderLayout.NORTH);
        card.add(bottom, BorderLayout.CENTER);
        return card;
    }

    private void addFieldRow(JPanel form, GridBagConstraints gbc, int row,
                             String label, JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(field, gbc);
    }

    private void handleAddTransaction() {
        String date = dateField.getText().trim();
        String desc = descField.getText().trim();
        String amtStr = amountField.getText().trim().replace(",", "");
        Account debit  = (Account) debitCombo.getSelectedItem();
        Account credit = (Account) creditCombo.getSelectedItem();

        if (date.isEmpty() || !isValidDate(date)) {
            setFormMessage("Please enter a valid date in YYYY-MM-DD format.", false); return;
        }
        if (desc.isEmpty()) { setFormMessage("Description cannot be empty.", false); return; }
        if (debit == null)  { setFormMessage("Please choose a debit account.", false); return; }
        if (credit == null) { setFormMessage("Please choose a credit account.", false); return; }
        if (debit == credit) {
            setFormMessage("Debit and credit accounts must differ.", false); return;
        }
        BigDecimal amount;
        try { amount = new BigDecimal(amtStr); }
        catch (NumberFormatException ex) {
            setFormMessage("Amount must be a valid number.", false); return;
        }
        if (amount.signum() <= 0) {
            setFormMessage("Amount must be greater than zero.", false); return;
        }

        transactions.add(new Transaction(date, desc, debit, credit, amount));
        saveToDataFile();

        descField.setText("");
        amountField.setText("");
        dateField.setText(LocalDate.now().toString());
        setFormMessage("Transaction added.", true);
        setStatus("Added 1 transaction.");
        refreshAll();
    }

    private boolean isValidDate(String s) {
        try {
            LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException e) { return false; }
    }

    // -------------------------------------------------- Tab 2: Transactions
    private JComponent buildTransactionsTab() {
        JPanel card = card();

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setBackground(CARD_BG);
        toolbar.setBorder(new EmptyBorder(0, 0, 12, 0));
        searchField = new JTextField(28);
        searchField.setPreferredSize(new Dimension(280, 28));
        JButton clear = new JButton("Clear");
        clear.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { searchField.setText(""); }
        });
        JButton recompute = makeBlueButton("Recompute Balances");
        recompute.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                refreshAll();
                setStatus("Balances recomputed.");
            }
        });
        toolbar.add(new JLabel("Search:"));
        toolbar.add(searchField);
        toolbar.add(clear);
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(recompute);

        txnModel = new DefaultTableModel(new String[]{
            "Date", "Description", "Debit Account", "Credit Account", "Amount"
        }, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        txnTable = new JTable(txnModel);
        styleTable(txnTable);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshTransactions(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshTransactions(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshTransactions(); }
        });

        card.add(toolbar, BorderLayout.NORTH);
        card.add(new JScrollPane(txnTable), BorderLayout.CENTER);
        return card;
    }

    private void refreshTransactions() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        txnModel.setRowCount(0);
        for (Transaction t : transactions) {
            String d  = t.description  == null ? "" : t.description.toLowerCase();
            String db = t.debit.name.toLowerCase();
            String cr = t.credit.name.toLowerCase();
            if (q.isEmpty() || d.contains(q) || db.contains(q) || cr.contains(q)) {
                txnModel.addRow(new Object[]{
                    t.date, t.description, t.debit.name, t.credit.name, MONEY.format(t.amount)
                });
            }
        }
    }

    // -------------------------------------------------- Tab 3: Accounts
    private JComponent buildAccountsTab() {
        JPanel card = card();
        accountsModel = new DefaultTableModel(new String[]{
            "Account", "Type", "Balance"
        }, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        accountsTable = new JTable(accountsModel);
        styleTable(accountsTable);
        card.add(new JScrollPane(accountsTable), BorderLayout.CENTER);
        return card;
    }

    private void refreshAccounts() {
        accountsModel.setRowCount(0);
        for (Account a : accounts) {
            accountsModel.addRow(new Object[]{
                a.name, a.type, MONEY.format(balanceOf(a))
            });
        }
    }

    // -------------------------------------------------- Tab 4: General Journal
    private JComponent buildJournalTab() {
        JPanel card = card();
        journalModel = new DefaultTableModel(new String[]{
            "Date", "Description", "Account", "Debit", "Credit"
        }, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        journalTable = new JTable(journalModel);
        styleTable(journalTable);
        card.add(new JScrollPane(journalTable), BorderLayout.CENTER);
        return card;
    }

    private void refreshJournal() {
        journalModel.setRowCount(0);
        for (Transaction t : transactions) {
            String amt = MONEY.format(t.amount);
            journalModel.addRow(new Object[]{
                t.date, t.description, t.debit.name, amt, ""
            });
            journalModel.addRow(new Object[]{
                "", "", "    " + t.credit.name, "", amt
            });
        }
    }

    // -------------------------------------------------- Tab 5: General Ledger
    private JComponent buildLedgerTab() {
        JPanel card = card();
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setBackground(CARD_BG);
        toolbar.setBorder(new EmptyBorder(0, 0, 12, 0));
        toolbar.add(new JLabel("Account:"));
        ledgerAccountCombo = new JComboBox<>();
        ledgerAccountCombo.setPreferredSize(new Dimension(300, 26));
        ledgerAccountCombo.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { refreshLedger(); }
        });
        toolbar.add(ledgerAccountCombo);

        ledgerModel = new DefaultTableModel(new String[]{
            "Date", "Description", "Debit Account", "Credit Account", "Amount", "Balance"
        }, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        ledgerTable = new JTable(ledgerModel);
        styleTable(ledgerTable);

        card.add(toolbar, BorderLayout.NORTH);
        card.add(new JScrollPane(ledgerTable), BorderLayout.CENTER);
        return card;
    }

    private void refreshLedger() {
        Account sel = (Account) ledgerAccountCombo.getSelectedItem();
        ledgerModel.setRowCount(0);
        if (sel == null) return;
        boolean debitNormal = "ASSET".equals(sel.type) || "EXPENSE".equals(sel.type);
        BigDecimal running = BigDecimal.ZERO;
        for (Transaction t : transactions) {
            boolean isDebit = t.debit == sel;
            boolean isCredit = t.credit == sel;
            if (!isDebit && !isCredit) continue;
            if (isDebit) running = running.add(debitNormal ? t.amount : t.amount.negate());
            else         running = running.add(debitNormal ? t.amount.negate() : t.amount);
            ledgerModel.addRow(new Object[]{
                t.date, t.description, t.debit.name, t.credit.name,
                MONEY.format(t.amount), MONEY.format(running)
            });
        }
    }

    // -------------------------------------------------- Tab 6: Balance Sheet
    private JComponent buildBalanceSheetTab() {
        JPanel card = card();

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(CARD_BG);
        toolbar.setBorder(new EmptyBorder(0, 0, 12, 0));
        JLabel title = new JLabel("Balance Sheet");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        toolbar.add(title, BorderLayout.WEST);
        JButton export = makeBlueButton("Export Sheet");
        export.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { exportBalanceSheet(); }
        });
        toolbar.add(export, BorderLayout.EAST);

        assetsModel = new DefaultTableModel(new String[]{"Assets", "Amount"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        leModel = new DefaultTableModel(new String[]{"Liabilities & Equity", "Amount"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        assetsTable = new BalanceSheetTable(assetsModel);
        leTable     = new BalanceSheetTable(leModel);
        styleTable(assetsTable);
        styleTable(leTable);

        JPanel tables = new JPanel(new GridLayout(1, 2, 16, 0));
        tables.setBackground(CARD_BG);
        tables.add(new JScrollPane(assetsTable));
        tables.add(new JScrollPane(leTable));

        card.add(toolbar, BorderLayout.NORTH);
        card.add(tables, BorderLayout.CENTER);
        return card;
    }

    private void refreshBalanceSheet() {
        assetsModel.setRowCount(0);
        leModel.setRowCount(0);
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiab   = BigDecimal.ZERO;
        BigDecimal totalEq     = BigDecimal.ZERO;
        BigDecimal totalInc    = BigDecimal.ZERO;
        BigDecimal totalExp    = BigDecimal.ZERO;

        for (Account a : accounts) {
            BigDecimal bal = balanceOf(a);
            if ("ASSET".equals(a.type)) {
                if (bal.signum() != 0)
                    assetsModel.addRow(new Object[]{a.name, MONEY.format(bal)});
                totalAssets = totalAssets.add(bal);
            } else if ("LIABILITY".equals(a.type)) {
                if (bal.signum() != 0)
                    leModel.addRow(new Object[]{a.name, MONEY.format(bal)});
                totalLiab = totalLiab.add(bal);
            } else if ("EQUITY".equals(a.type)) {
                if (bal.signum() != 0)
                    leModel.addRow(new Object[]{a.name, MONEY.format(bal)});
                totalEq = totalEq.add(bal);
            } else if ("INCOME".equals(a.type)) {
                totalInc = totalInc.add(bal);
            } else if ("EXPENSE".equals(a.type)) {
                totalExp = totalExp.add(bal);
            }
        }
        BigDecimal netIncome = totalInc.subtract(totalExp);
        if (netIncome.signum() != 0) {
            leModel.addRow(new Object[]{"Net Income", MONEY.format(netIncome)});
        }
        assetsModel.addRow(new Object[]{"Total Assets", MONEY.format(totalAssets)});
        BigDecimal totalLiabEq = totalLiab.add(totalEq).add(netIncome);
        leModel.addRow(new Object[]{"Total Liabilities & Equity", MONEY.format(totalLiabEq)});
    }

    private void exportBalanceSheet() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export Balance Sheet");
        fc.setSelectedFile(new File("balance-sheet.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Accounting System - Balance Sheet\n");
            sb.append("Generated: ").append(LocalDate.now()).append("\n\n");
            sb.append("ASSETS\n");
            for (int i = 0; i < assetsModel.getRowCount(); i++) {
                sb.append(String.format("%-30s %15s%n",
                    assetsModel.getValueAt(i, 0), assetsModel.getValueAt(i, 1)));
            }
            sb.append("\nLIABILITIES & EQUITY\n");
            for (int i = 0; i < leModel.getRowCount(); i++) {
                sb.append(String.format("%-30s %15s%n",
                    leModel.getValueAt(i, 0), leModel.getValueAt(i, 1)));
            }
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            setStatus("Balance sheet exported to " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -------------------------------------------------- Load / Save (explicit)
    private void doSave() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Data");
        fc.setSelectedFile(new File("accounting-data.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Accounting System data export\n");
            for (Transaction t : transactions) {
                sb.append(t.date).append('|')
                  .append(escape(t.description)).append('|')
                  .append(t.debit.name).append('|')
                  .append(t.credit.name).append('|')
                  .append(t.amount.toPlainString()).append('\n');
            }
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            setStatus("Saved " + transactions.size() + " transactions to " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doLoad() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load Data");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            transactions.clear();
            int loaded = 0;
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length != 5) continue;
                String date  = parts[0].trim();
                String desc  = unescape(parts[1]);
                String dName = parts[2].trim();
                String cName = parts[3].trim();
                BigDecimal amount;
                try { amount = new BigDecimal(parts[4].trim()); }
                catch (NumberFormatException ex) { continue; }
                Account d = findAccount(dName);
                Account c = findAccount(cName);
                if (d == null || c == null || d == c ||
                    amount.signum() <= 0 || !isValidDate(date)) continue;
                transactions.add(new Transaction(date, desc, d, c, amount));
                loaded++;
            }
            saveToDataFile();
            refreshAll();
            setStatus("Loaded " + loaded + " transactions from " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void confirmExit() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Exit Accounting System?", "Exit",
            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            saveToDataFile();
            System.exit(0);
        }
    }

    // -------------------------------------------------- accounting math
    /** Derived balance for one account in its normal-balance direction. */
    private BigDecimal balanceOf(Account a) {
        BigDecimal debits  = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Transaction t : transactions) {
            if (t.debit  == a) debits  = debits.add(t.amount);
            if (t.credit == a) credits = credits.add(t.amount);
        }
        boolean debitNormal = "ASSET".equals(a.type) || "EXPENSE".equals(a.type);
        return debitNormal ? debits.subtract(credits) : credits.subtract(debits);
    }

    // -------------------------------------------------- refresh + helpers
    private void refreshAll() {
        Account prevDebit  = (Account) debitCombo.getSelectedItem();
        Account prevCredit = (Account) creditCombo.getSelectedItem();
        Account prevLedger = (Account) ledgerAccountCombo.getSelectedItem();

        DefaultComboBoxModel<Account> m1 = new DefaultComboBoxModel<>();
        DefaultComboBoxModel<Account> m2 = new DefaultComboBoxModel<>();
        DefaultComboBoxModel<Account> m3 = new DefaultComboBoxModel<>();
        for (Account a : accounts) {
            m1.addElement(a);
            m2.addElement(a);
            m3.addElement(a);
        }
        debitCombo.setModel(m1);
        creditCombo.setModel(m2);
        ledgerAccountCombo.setModel(m3);

        if (prevDebit  != null) debitCombo.setSelectedItem(prevDebit);
        if (prevCredit != null) creditCombo.setSelectedItem(prevCredit);
        if (prevLedger != null) ledgerAccountCombo.setSelectedItem(prevLedger);

        refreshTransactions();
        refreshAccounts();
        refreshJournal();
        refreshLedger();
        refreshBalanceSheet();
    }

    private void setStatus(String text) { statusLabel.setText(text); }

    private void setFormMessage(String text, boolean ok) {
        formMsg.setText(text);
        if (ok) {
            formMsg.setForeground(GREEN);
            formMsg.setBackground(GREEN_BG);
        } else {
            formMsg.setForeground(RED);
            formMsg.setBackground(RED_BG);
        }
    }

    // -------------------------------------------------- escaping for pipe format
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n");
    }
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) {
                switch (ch) {
                    case '\\': sb.append('\\'); break;
                    case 'p':  sb.append('|');  break;
                    case 'n':  sb.append('\n'); break;
                    default:   sb.append(ch);    break;
                }
                esc = false;
            } else if (ch == '\\') {
                esc = true;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------- table styling helpers
    private void styleTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(new Color(0xe3, 0xf2, 0xfd));
        table.setSelectionForeground(Color.BLACK);
        table.setFont(table.getFont().deriveFont(13f));
        JTableHeader header = table.getTableHeader();
        header.setBackground(NAVY);
        header.setForeground(Color.WHITE);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setPreferredSize(new Dimension(header.getWidth(), 32));
    }

    /**
     * JTable whose Total rows (those whose first column starts with "Total")
     * render bold with a light tinted background. Used by the Balance Sheet.
     */
    private static class BalanceSheetTable extends JTable {
        BalanceSheetTable(DefaultTableModel model) { super(model); }
        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
            Component c = super.prepareRenderer(renderer, row, col);
            Object v = getModel().getValueAt(row, 0);
            boolean isTotal = v != null && v.toString().startsWith("Total");
            if (isTotal) {
                c.setFont(c.getFont().deriveFont(Font.BOLD));
                if (!isRowSelected(row)) c.setBackground(TOTAL_BG);
            } else {
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
                if (!isRowSelected(row)) c.setBackground(Color.WHITE);
            }
            return c;
        }
    }

    // -------------------------------------------------- data classes
    private static final class Account {
        final int id;
        final String name, type;
        Account(int id, String name, String type) {
            this.id = id; this.name = name; this.type = type;
        }
        @Override public String toString() { return name + " [" + type + "]"; }
    }

    private static final class Transaction {
        final String date, description;
        final Account debit, credit;
        final BigDecimal amount;
        Transaction(String date, String description, Account debit, Account credit, BigDecimal amount) {
            this.date = date; this.description = description;
            this.debit = debit; this.credit = credit; this.amount = amount;
        }
    }
}