// TV 49 East PayPal wallet endpoint.
// Required secrets: PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET, PAYPAL_BASE_URL.
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status, headers: { ...cors, "Content-Type": "application/json" } });
Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);
  const auth = req.headers.get("Authorization"); if (!auth?.startsWith("Bearer ")) return json({ error: "Authentication required" }, 401);
  const supabaseUrl=Deno.env.get("SUPABASE_URL"), serviceKey=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"), clientId=Deno.env.get("PAYPAL_CLIENT_ID"), clientSecret=Deno.env.get("PAYPAL_CLIENT_SECRET");
  const paypalBase=(Deno.env.get("PAYPAL_BASE_URL")||"https://api-m.sandbox.paypal.com").replace(/\/$/,"");
  if(!supabaseUrl||!serviceKey||!clientId||!clientSecret)return json({error:"PayPal is not configured"},503);
  const jwt=auth.slice(7); const userRes=await fetch(`${supabaseUrl}/auth/v1/user`,{headers:{apikey:serviceKey,Authorization:`Bearer ${jwt}`}}); if(!userRes.ok)return json({error:"Invalid session"},401); const user=await userRes.json();
  const payload=await req.json().catch(()=>null); const action=String(payload?.action||"create");
  const tokenRes=await fetch(`${paypalBase}/v1/oauth2/token`,{method:"POST",headers:{Authorization:`Basic ${btoa(`${clientId}:${clientSecret}`)}`,"Content-Type":"application/x-www-form-urlencoded"},body:"grant_type=client_credentials"});
  if(!tokenRes.ok)return json({error:"PayPal authentication failed"},502); const accessToken=(await tokenRes.json()).access_token;
  if(action==="create"){
    const amountCents=Number(payload?.amount_cents),currency=String(payload?.currency||"USD").toUpperCase();
    if(!Number.isInteger(amountCents)||amountCents<100||amountCents>1000000)return json({error:"Top-up must be between 1.00 and 10,000.00"},400); if(!/^[A-Z]{3}$/.test(currency))return json({error:"Invalid currency"},400);
    const amount=(amountCents/100).toFixed(2);
    const txRes=await fetch(`${supabaseUrl}/rest/v1/wallet_transactions`,{method:"POST",headers:{apikey:serviceKey,Authorization:`Bearer ${serviceKey}`,"Content-Type":"application/json",Prefer:"return=representation"},body:JSON.stringify({user_id:user.id,kind:"topup",amount_cents:amountCents,currency,direction:"credit",status:"pending",provider:"paypal",description:"PayPal wallet top-up"})});
    if(!txRes.ok)return json({error:"Unable to create wallet transaction"},500); const tx=(await txRes.json())[0];
    const orderRes=await fetch(`${paypalBase}/v2/checkout/orders`,{method:"POST",headers:{Authorization:`Bearer ${accessToken}`,"Content-Type":"application/json","PayPal-Request-Id":tx.id},body:JSON.stringify({intent:"CAPTURE",purchase_units:[{reference_id:tx.id,custom_id:tx.id,amount:{currency_code:currency,value:amount},description:"TV 49 East wallet top-up"}],application_context:{brand_name:"TV 49 East",user_action:"PAY_NOW",return_url:"tv49://wallet/paypal/success",cancel_url:"tv49://wallet/paypal/cancel"}})});
    const order=await orderRes.json(); if(!orderRes.ok)return json({error:"Unable to create PayPal order"},502); const approval=order.links?.find((l:any)=>l.rel==="approve")?.href||null;
    await fetch(`${supabaseUrl}/rest/v1/wallet_transactions?id=eq.${encodeURIComponent(tx.id)}`,{method:"PATCH",headers:{apikey:serviceKey,Authorization:`Bearer ${serviceKey}`,"Content-Type":"application/json"},body:JSON.stringify({provider_order_id:order.id})});
    await fetch(`${supabaseUrl}/rest/v1/paypal_orders`,{method:"POST",headers:{apikey:serviceKey,Authorization:`Bearer ${serviceKey}`,"Content-Type":"application/json"},body:JSON.stringify({user_id:user.id,wallet_transaction_id:tx.id,paypal_order_id:order.id,amount_cents:amountCents,currency,approval_url:approval})});
    return json({order_id:order.id,approval_url:approval,amount_cents:amountCents,currency});
  }
  if(action==="capture"){
    const orderId=String(payload?.order_id||"").trim(); if(!orderId)return json({error:"order_id is required"},400);
    const own=await fetch(`${supabaseUrl}/rest/v1/paypal_orders?paypal_order_id=eq.${encodeURIComponent(orderId)}&user_id=eq.${encodeURIComponent(user.id)}&select=paypal_order_id,status,amount_cents,currency`,{headers:{apikey:serviceKey,Authorization:`Bearer ${serviceKey}`}}); const rows=await own.json(); if(!rows?.length)return json({error:"Order not found"},404); if(rows[0].status==="captured")return json({status:"captured",order_id:orderId});
    const captureRes=await fetch(`${paypalBase}/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`,{method:"POST",headers:{Authorization:`Bearer ${accessToken}`,"Content-Type":"application/json","PayPal-Request-Id":orderId}}); const capture=await captureRes.json();
    if(!captureRes.ok||capture.status!=="COMPLETED")return json({error:"PayPal capture failed",status:capture.status||"FAILED"},502);
    const unit=capture.purchase_units?.[0], payment=unit?.payments?.captures?.[0], captureId=payment?.id, capturedCurrency=payment?.amount?.currency_code, capturedValue=payment?.amount?.value;
    if(!captureId||!capturedCurrency||!capturedValue)return json({error:"PayPal capture data incomplete"},502);
    const expected=(Number(rows[0].amount_cents)/100).toFixed(2); if(capturedCurrency!==rows[0].currency||capturedValue!==expected)return json({error:"Captured amount does not match wallet order"},409);
    const rpc=await fetch(`${supabaseUrl}/rest/v1/rpc/finalize_paypal_topup`,{method:"POST",headers:{apikey:serviceKey,Authorization:`Bearer ${serviceKey}`,"Content-Type":"application/json"},body:JSON.stringify({p_order_id:orderId,p_capture_id:captureId})}); if(!rpc.ok)return json({error:"Payment captured but wallet finalization failed; support reconciliation required"},500);
    return json({status:"captured",order_id:orderId,capture_id:captureId});
  }
  return json({error:"Unknown action"},400);
});
