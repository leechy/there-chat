# Server build and deploy. See docs/DEPLOY.md.
#
#   make test        run server tests
#   make build       build the Linux binary into build/
#   make provision   one-time droplet setup (swap, firewall, Caddy, systemd)
#   make deploy      test, build, upload, restart, check health
#   make web         upload the static site (web/)
#   make logs        follow server logs
#
# Before DNS works, pass the IP: make deploy HOST=root@<droplet-ip>

HOST ?= root@api.there-chat.com
GO   ?= go
BIN  := build/there-linux-amd64

.PHONY: test build provision deploy web logs status

test:
	cd server && $(GO) test ./...

build:
	mkdir -p build
	cd server && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -trimpath -ldflags "-s -w" -o ../$(BIN) ./cmd/there

provision:
	ssh $(HOST) 'mkdir -p /tmp/there-deploy'
	scp deploy/setup.sh deploy/there.service deploy/Caddyfile $(HOST):/tmp/there-deploy/
	ssh $(HOST) 'bash /tmp/there-deploy/setup.sh'

deploy: test build
	scp $(BIN) $(HOST):/opt/there/there.new
	ssh $(HOST) 'chmod 755 /opt/there/there.new && mv /opt/there/there.new /opt/there/there && systemctl restart there && sleep 1 && systemctl is-active there && curl -fsS 127.0.0.1:8080/healthz'

web:
	rsync -az --delete web/ $(HOST):/var/www/there/

logs:
	ssh $(HOST) 'journalctl -u there -f'

status:
	ssh $(HOST) 'systemctl --no-pager status there caddy | head -30'
