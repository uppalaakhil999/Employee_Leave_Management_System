# Employee Leave Management System

A beginner-friendly Java console application that uses JDBC and MySQL to register employees, submit leave requests, review requests, and track balances.

## Requirements

- Java 8 or newer
- MySQL 8 or newer
- Maven

## Database setup

1. Start MySQL.
2. Run the statements in `database.sql` using MySQL Workbench or the MySQL command line.
3. The schema gives each new employee 20 calendar leave days.

For example, from the project folder:

```text
mysql -u root -p < database.sql
```

## Configure and run

The app connects using these environment variables:

- `DB_URL` (default: `jdbc:mysql://localhost:3306/employee_leave_management`)
- `DB_USER` (default: `root`)
- `DB_PASSWORD` (default: empty)

Set `DB_PASSWORD` to your local MySQL password; do not put your password in the source code.

From PowerShell, for example:

```powershell
$env:DB_USER = "root"
$env:DB_PASSWORD = "your-local-password"
mvn compile exec:java
```

## Menu

1. Register an employee. The employee and their starting balance are inserted in one transaction.
2. Apply for leave. Dates are inclusive and counted as calendar days.
3. Approve or reject a pending request by request ID. Approval deducts the balance and changes the request status in one transaction. A request cannot be approved when the employee has too few days remaining.
4. Check an employee's available leave balance.
5. View all leave requests and their statuses.

Pending requests do not reserve leave days. The balance is checked when a manager approves each request.
