# 本机 HTTPS 测试证书

本目录证书和 server.p12 仅供本机自动化测试，不得部署到实际环境。
PKCS12 密码为固定测试值 test-only；CA 私钥未保存。
服务端证书仅包含 DNS localhost，不包含 127.0.0.1，用于验证主机名不匹配时必须失败。
wrong-ca.pem 用于验证信任隔离，leaf.pem 用于验证不能将普通服务端证书作为 CA 保存。
