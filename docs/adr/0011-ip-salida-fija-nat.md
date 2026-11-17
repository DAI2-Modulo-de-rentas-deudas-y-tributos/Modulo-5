# ADR 0011: IP de salida fija del backend con NAT Gateway

- Estado: aceptado
- Fecha: 2026-11-16

## Contexto

Core (Módulo 9) autoriza por IP de origen (`/32`) a los módulos que se suscriben y publican
eventos. Las tareas Fargate de M5 corrían en las subredes públicas con
`assign_public_ip = true`. Salían por el Internet Gateway con una IP pública efímera que
cambiaba con cada deploy, rollback o reemplazo de tarea, y con cada paso de
`scripts/test/core-http-trial.sh`. Ninguna regla en Core podía seguirla.

## Decisión

- El módulo `network` crea dos subredes privadas `app` (`10.<n>.20.0/24` y `10.<n>.21.0/24`)
  y un NAT Gateway con Elastic IP en la primera subred pública. La tabla de rutas `app` sale
  a Internet sólo por ese NAT.
- El servicio ECS usa las subredes `app` con `assign_public_ip = false`. El ALB sigue en las
  subredes públicas y PostgreSQL en las aisladas, sin cambios.
- La Elastic IP es la IP que se autoriza en Core. Cada ambiente la expone en el output
  `nat_public_ip` de Terraform.
- El cambio aplica a los tres ambientes: el módulo es compartido. DEV crea y destruye su NAT
  junto con el ambiente.
- Los roles `github-<ambiente>` reciben en el bootstrap `ec2:AllocateAddress`,
  `ec2:ReleaseAddress`, `ec2:DisassociateAddress`, `ec2:CreateNatGateway` y
  `ec2:DeleteNatGateway`.

## Alternativas

- Un NAT por zona: tolera la caída de una zona, pero duplica el costo y Core debería autorizar
  dos IPs. Descartado por ahora: sin el NAT de la zona 1, el backend sigue atendiendo HTTP y
  la outbox acumula eventos hasta que vuelve la salida.
- Instancia NAT propia en EC2: es más barata, pero hay que parchearla y no tiene alta
  disponibilidad gestionada. Descartado.
- Autorizar en Core la IP efímera de la tarea en cada ventana: es el procedimiento anterior,
  y se rompe con cada redeploy. Descartado.
- Autorizar en Core un rango de AWS: abre la puerta a cualquier cliente de la región.
  Descartado.

## Consecuencias

- Costo fijo de unos US$ 33 por mes por ambiente persistente (TEST y PROD), más US$ 0,045 por
  GB procesado. El apagado nocturno de TEST no detiene el NAT.
- La IP sólo cambia si se destruye la red del ambiente. Destruir TEST desde su workflow obliga
  a pedirle a Core que autorice la nueva IP.
- El bootstrap se aplica antes que los ambientes: sin esos permisos, el `apply` de los
  ambientes falla con `UnauthorizedOperation` al crear la EIP.
- Al cambiar de subred, ECS reemplaza la tarea con un deploy rolling. El ALB la registra con
  su IP privada nueva.
