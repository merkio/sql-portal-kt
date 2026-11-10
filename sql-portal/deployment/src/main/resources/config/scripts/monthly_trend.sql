-- Referenced from scripts.conf via `sql_file`.
SELECT strftime('%Y-%m', sold_on) AS month,
       COUNT(*)                   AS orders,
       ROUND(SUM(amount), 2)      AS revenue
FROM sales
GROUP BY month
ORDER BY month
