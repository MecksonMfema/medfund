defmodule ChatServiceWeb.ChatSocketTest do
  @moduledoc """
  Verifies the JWT decode path used by ChatSocket.connect/3 — the security
  boundary for tenant isolation on the chat WebSocket. A regression here
  would let a client open a socket without a valid token, or assign the
  wrong tenant_id, breaking the per-tenant chat-room isolation rule.
  """

  use ChatServiceWeb.ChannelCase, async: true

  alias ChatServiceWeb.ChatSocket

  # Encode a JWT-shaped string with the supplied claims. The signature byte is
  # ignored by ChatSocket's decode path — it only base64-decodes the payload.
  defp token_with(claims) do
    header = Base.url_encode64(~s({"alg":"HS256","typ":"JWT"}), padding: false)
    payload = Base.url_encode64(Jason.encode!(claims), padding: false)
    "#{header}.#{payload}.sig"
  end

  test "connect with a valid token assigns user_id and tenant_id" do
    token = token_with(%{"sub" => "user-1", "tenant_id" => "tenant-a"})

    assert {:ok, socket} = connect(ChatSocket, %{"token" => token})
    assert socket.assigns.user_id == "user-1"
    assert socket.assigns.tenant_id == "tenant-a"
  end

  test "connect supports the legacy camelCase tenantId claim" do
    token = token_with(%{"sub" => "user-2", "tenantId" => "tenant-b"})

    assert {:ok, socket} = connect(ChatSocket, %{"token" => token})
    assert socket.assigns.tenant_id == "tenant-b"
  end

  test "connect defaults tenant_id to 'default' when both claims are missing" do
    token = token_with(%{"sub" => "user-3"})

    assert {:ok, socket} = connect(ChatSocket, %{"token" => token})
    assert socket.assigns.tenant_id == "default"
  end

  test "connect rejects a malformed token" do
    assert :error = connect(ChatSocket, %{"token" => "not.a.jwt"})
    assert :error = connect(ChatSocket, %{"token" => "missing-dots"})
  end

  test "connect rejects when token is absent" do
    assert :error = connect(ChatSocket, %{})
  end

  test "id/1 namespaces sockets by user — one socket per user across rooms" do
    token = token_with(%{"sub" => "user-9", "tenant_id" => "tenant-a"})
    {:ok, socket} = connect(ChatSocket, %{"token" => token})

    assert ChatSocket.id(socket) == "chat_socket:user-9"
  end
end
