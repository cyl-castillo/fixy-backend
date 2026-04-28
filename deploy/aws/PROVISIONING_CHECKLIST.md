# Fixy AWS provisioning checklist

## Recommended baseline
- Lightsail instance: `micro_3_0` (1 GB RAM)
- Static IP attached
- Ubuntu LTS
- DNS for `api.fixy.com.uy` pointed to the static IP

## 1. Base packages
```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y nginx postgresql postgresql-contrib openjdk-21-jre-headless certbot python3-certbot-nginx unzip
```

## 2. System user and directories
```bash
sudo useradd --system --home /opt/fixy-backend --shell /usr/sbin/nologin fixy || true
sudo mkdir -p /opt/fixy-backend
sudo chown -R fixy:fixy /opt/fixy-backend
sudo touch /var/log/fixy-backend.log
sudo chown fixy:fixy /var/log/fixy-backend.log
```

## 3. PostgreSQL
```bash
sudo -u postgres psql <<'SQL'
CREATE USER fixy WITH PASSWORD 'CHANGE_ME_DB_PASSWORD';
CREATE DATABASE fixy OWNER fixy;
SQL
```

## 4. App env file
```bash
sudo cp deploy/aws/fixy-backend.env.example /etc/fixy-backend.env
sudo chmod 600 /etc/fixy-backend.env
sudo nano /etc/fixy-backend.env
```

## 5. Build and upload jar
From local machine:
```bash
cd /home/father/Documents/workspaces/fixy-backend
mvn package -q
scp target/fixy-backend-0.0.1-SNAPSHOT.jar ubuntu@SERVER_IP:/tmp/fixy-backend.jar
```

On server:
```bash
sudo mv /tmp/fixy-backend.jar /opt/fixy-backend/fixy-backend.jar
sudo chown fixy:fixy /opt/fixy-backend/fixy-backend.jar
```

## 6. systemd service
```bash
sudo cp deploy/aws/fixy-backend.service /etc/systemd/system/fixy-backend.service
sudo systemctl daemon-reload
sudo systemctl enable --now fixy-backend.service
sudo systemctl status fixy-backend.service --no-pager
```

## 7. Nginx
```bash
sudo cp deploy/aws/nginx-api.fixy.com.uy.conf /etc/nginx/sites-available/api.fixy.com.uy.conf
sudo ln -s /etc/nginx/sites-available/api.fixy.com.uy.conf /etc/nginx/sites-enabled/api.fixy.com.uy.conf
sudo nginx -t
sudo systemctl reload nginx
```

## 8. TLS
```bash
sudo certbot --nginx -d api.fixy.com.uy
```

## 9. Verify
```bash
curl -i http://127.0.0.1:8080/api/health
curl -i https://api.fixy.com.uy/api/health
systemctl status fixy-backend.service --no-pager
journalctl -u fixy-backend.service -n 100 --no-pager
```

## 10. Frontend note
Frontend can stay on S3 static hosting and point its API base URL to:
```text
https://api.fixy.com.uy
```
