package com.example.leavemanagement;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Scanner;

public class LeaveManagementApp {
    private static final int STARTING_LEAVE_DAYS = 20;
    private static final Scanner SCANNER = new Scanner(System.in);

    public static void main(String[] args) {
        String url = environmentValue(
                "DB_URL",
                "jdbc:mysql://localhost:3306/employee_leave_management");
        String username = environmentValue("DB_USER", "root");
        String password = environmentValue("DB_PASSWORD", "");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            System.out.println("Connected to the leave management database.");
            runMenu(connection);
        } catch (SQLException e) {
            System.out.println("Database connection failed: " + e.getMessage());
            System.out.println("Check that MySQL is running, database.sql was executed, and DB settings are correct.");
        }
    }

    private static void runMenu(Connection connection) {
        boolean running = true;
        while (running) {
            printMenu();
            int choice = readInt("Choose an option: ");

            try {
                switch (choice) {
                    case 1:
                        registerEmployee(connection);
                        break;
                    case 2:
                        applyForLeave(connection);
                        break;
                    case 3:
                        reviewLeaveRequest(connection);
                        break;
                    case 4:
                        showLeaveBalance(connection);
                        break;
                    case 5:
                        showLeaveRequests(connection);
                        break;
                    case 6:
                        running = false;
                        break;
                    default:
                        System.out.println("Please choose a number from 1 to 6.");
                }
            } catch (SQLException e) {
                System.out.println("Database operation failed: " + e.getMessage());
            }
        }
        System.out.println("Goodbye.");
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("=== Employee Leave Management ===");
        System.out.println("1. Register employee");
        System.out.println("2. Apply for leave");
        System.out.println("3. Approve or reject a leave request");
        System.out.println("4. Check leave balance");
        System.out.println("5. View leave requests");
        System.out.println("6. Exit");
    }

    private static void registerEmployee(Connection connection) throws SQLException {
        String name = readRequiredText("Employee name: ");
        String email = readRequiredText("Email: ");
        String department = readRequiredText("Department: ");

        String employeeSql = "INSERT INTO employees (name, email, department) VALUES (?, ?, ?)";
        String balanceSql = "INSERT INTO leave_balance (employee_id, available_days) VALUES (?, ?)";
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            int employeeId;
            try (PreparedStatement statement = connection.prepareStatement(
                    employeeSql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, email);
                statement.setString(3, department);
                statement.executeUpdate();

                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Could not retrieve the new employee ID.");
                    }
                    employeeId = keys.getInt(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(balanceSql)) {
                statement.setInt(1, employeeId);
                statement.setInt(2, STARTING_LEAVE_DAYS);
                statement.executeUpdate();
            }

            connection.commit();
            System.out.println("Employee registered. Employee ID: " + employeeId
                    + "; starting leave balance: " + STARTING_LEAVE_DAYS + " days.");
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void applyForLeave(Connection connection) throws SQLException {
        int employeeId = readInt("Employee ID: ");
        if (!employeeExists(connection, employeeId)) {
            System.out.println("No employee was found with that ID.");
            return;
        }

        LocalDate startDate = readDate("Start date (YYYY-MM-DD): ");
        LocalDate endDate = readDate("End date (YYYY-MM-DD): ");
        if (endDate.isBefore(startDate)) {
            System.out.println("The end date must be on or after the start date.");
            return;
        }

        int daysRequested = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        String reason = readRequiredText("Reason: ");
        String sql = "INSERT INTO leave_requests "
                + "(employee_id, start_date, end_date, days_requested, reason) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(startDate));
            statement.setDate(3, Date.valueOf(endDate));
            statement.setInt(4, daysRequested);
            statement.setString(5, reason);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    System.out.println("Leave request submitted. Request ID: " + keys.getInt(1)
                            + " (" + daysRequested + " calendar days).");
                }
            }
        }
    }

    private static void reviewLeaveRequest(Connection connection) throws SQLException {
        showPendingRequests(connection);
        int requestId = readInt("Request ID to review: ");
        String decision;
        while (true) {
            decision = readRequiredText("Enter A to approve or R to reject: ");
            if (decision.equalsIgnoreCase("A") || decision.equalsIgnoreCase("R")) {
                break;
            }
            System.out.println("Please enter A or R.");
        }

        boolean approve = decision.equalsIgnoreCase("A");
        reviewRequestInTransaction(connection, requestId, approve);
    }

    private static void reviewRequestInTransaction(
            Connection connection, int requestId, boolean approve) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            int employeeId;
            int daysRequested;
            String status;
            String requestSql = "SELECT employee_id, days_requested, status "
                    + "FROM leave_requests WHERE request_id = ? FOR UPDATE";
            try (PreparedStatement statement = connection.prepareStatement(requestSql)) {
                statement.setInt(1, requestId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        System.out.println("No leave request was found with that ID.");
                        return;
                    }
                    employeeId = result.getInt("employee_id");
                    daysRequested = result.getInt("days_requested");
                    status = result.getString("status");
                }
            }

            if (!"PENDING".equals(status)) {
                connection.rollback();
                System.out.println("This request has already been reviewed.");
                return;
            }

            if (approve) {
                String balanceSql = "UPDATE leave_balance "
                        + "SET available_days = available_days - ? "
                        + "WHERE employee_id = ? AND available_days >= ?";
                try (PreparedStatement statement = connection.prepareStatement(balanceSql)) {
                    statement.setInt(1, daysRequested);
                    statement.setInt(2, employeeId);
                    statement.setInt(3, daysRequested);
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        System.out.println("Not approved: the employee does not have enough leave days.");
                        return;
                    }
                }
            }

            String newStatus = approve ? "APPROVED" : "REJECTED";
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE leave_requests SET status = ? WHERE request_id = ?")) {
                statement.setString(1, newStatus);
                statement.setInt(2, requestId);
                statement.executeUpdate();
            }

            connection.commit();
            System.out.println("Request " + requestId + " " + newStatus.toLowerCase() + ".");
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void showLeaveBalance(Connection connection) throws SQLException {
        int employeeId = readInt("Employee ID: ");
        String sql = "SELECT e.name, b.available_days FROM employees e "
                + "JOIN leave_balance b ON e.employee_id = b.employee_id WHERE e.employee_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, employeeId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    System.out.println(result.getString("name") + " has "
                            + result.getInt("available_days") + " leave days available.");
                } else {
                    System.out.println("No employee was found with that ID.");
                }
            }
        }
    }

    private static void showLeaveRequests(Connection connection) throws SQLException {
        String sql = "SELECT r.request_id, e.name, r.start_date, r.end_date, "
                + "r.days_requested, r.reason, r.status "
                + "FROM leave_requests r JOIN employees e ON r.employee_id = e.employee_id "
                + "ORDER BY r.request_id";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            printRequests(result);
        }
    }

    private static void showPendingRequests(Connection connection) throws SQLException {
        String sql = "SELECT r.request_id, e.name, r.start_date, r.end_date, "
                + "r.days_requested, r.reason, r.status "
                + "FROM leave_requests r JOIN employees e ON r.employee_id = e.employee_id "
                + "WHERE r.status = 'PENDING' ORDER BY r.request_id";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            System.out.println("\nPending leave requests:");
            printRequests(result);
        }
    }

    private static void printRequests(ResultSet result) throws SQLException {
        boolean found = false;
        while (result.next()) {
            found = true;
            System.out.printf(
                    "ID: %d | Employee: %s | %s to %s | Days: %d | Status: %s | Reason: %s%n",
                    result.getInt("request_id"),
                    result.getString("name"),
                    result.getDate("start_date"),
                    result.getDate("end_date"),
                    result.getInt("days_requested"),
                    result.getString("status"),
                    result.getString("reason"));
        }
        if (!found) {
            System.out.println("No requests found.");
        }
    }

    private static boolean employeeExists(Connection connection, int employeeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT employee_id FROM employees WHERE employee_id = ?")) {
            statement.setInt(1, employeeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = SCANNER.nextLine().trim();
            try {
                return Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a whole number.");
            }
        }
    }

    private static LocalDate readDate(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = SCANNER.nextLine().trim();
            try {
                return LocalDate.parse(input);
            } catch (DateTimeParseException e) {
                System.out.println("Please use the YYYY-MM-DD date format.");
            }
        }
    }

    private static String readRequiredText(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = SCANNER.nextLine().trim();
            if (!input.isEmpty()) {
                return input;
            }
            System.out.println("This value cannot be empty.");
        }
    }

    private static String environmentValue(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }
}
