# Fixy Backend Recovery

## Restaurar desde backup local

### 1. Parar el servicio

```bash
sudo systemctl stop fixy-backend.service
```

### 2. Ver backups disponibles

```bash
cd /home/father/Documents/workspaces/fixy-backend
find backups -mindepth 1 -maxdepth 1 -type d | sort
```

### 3. Elegir backup y restaurar

Ejemplo usando `backups/latest`:

```bash
cd /home/father/Documents/workspaces/fixy-backend
rm -f data/*
cp -a backups/latest/. data/
```

Si quieres uno específico:

```bash
cd /home/father/Documents/workspaces/fixy-backend
rm -f data/*
cp -a backups/AAAA-MM-DD-HHMMSS/. data/
```

### 4. Levantar servicio

```bash
sudo systemctl start fixy-backend.service
```

### 5. Verificar salud

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/status.sh
bash scripts/verify_ops.sh
```

## Precauciones

- Restaurar siempre con el servicio parado.
- Si el problema es lógico y no de datos, revisar también config y logs antes de volver atrás.
- Si el backup es muy viejo, validar compatibilidad con cambios recientes.
