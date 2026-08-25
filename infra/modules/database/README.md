# Modulo database

Crea PostgreSQL en Amazon RDS dentro de subredes aisladas. La instancia usa
almacenamiento cifrado, backups automatizados y una clave maestra generada y
rotada por RDS mediante Secrets Manager. Terraform no recibe ni almacena la
clave en texto plano.
