package com.mrcrayfish.vehicle.entity;

import com.mrcrayfish.vehicle.client.VehicleHelper;
import com.mrcrayfish.vehicle.common.SurfaceHelper;
import com.mrcrayfish.vehicle.network.PacketHandler;
import com.mrcrayfish.vehicle.network.datasync.VehicleDataValue;
import com.mrcrayfish.vehicle.network.message.MessagePlaneInput;
import com.mrcrayfish.vehicle.util.CommonUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.Constants;

/**
 * Author: MrCrayfish
 */
public abstract class PlaneEntity extends PoweredVehicleEntity
{
    protected static final DataParameter<Float> LIFT = EntityDataManager.defineId(PlaneEntity.class, DataSerializers.FLOAT);
    protected static final DataParameter<Float> FORWARD_INPUT = EntityDataManager.defineId(PlaneEntity.class, DataSerializers.FLOAT);
    protected static final DataParameter<Float> SIDE_INPUT = EntityDataManager.defineId(PlaneEntity.class, DataSerializers.FLOAT);
    protected static final DataParameter<Float> PLANE_ROLL = EntityDataManager.defineId(PlaneEntity.class, DataSerializers.FLOAT);

    protected final VehicleDataValue<Float> lift = new VehicleDataValue<>(this, LIFT);
    protected final VehicleDataValue<Float> forwardInput = new VehicleDataValue<>(this, FORWARD_INPUT);
    protected final VehicleDataValue<Float> sideInput = new VehicleDataValue<>(this, SIDE_INPUT);
    protected final VehicleDataValue<Float> planeRoll = new VehicleDataValue<>(this, PLANE_ROLL);

    protected Vector3d velocity = Vector3d.ZERO;
    protected float propellerSpeed;
    protected float flapAngle;

    @OnlyIn(Dist.CLIENT)
    protected float propellerRotation;
    @OnlyIn(Dist.CLIENT)
    protected float prevPropellerRotation;

    protected PlaneEntity(EntityType<?> entityType, World worldIn)
    {
        super(entityType, worldIn);
        this.setSteeringSpeed(5);
    }

    @Override
    public void defineSynchedData()
    {
        super.defineSynchedData();
        this.entityData.define(LIFT, 0F);
        this.entityData.define(FORWARD_INPUT, 0F);
        this.entityData.define(SIDE_INPUT, 0F);
        this.entityData.define(PLANE_ROLL, 0F);
    }

    @Override
    public void updateVehicleMotion()
    {
        this.motion = Vector3d.ZERO;

        this.updateRotorSpeed();

        // Updates the planes roll based on input from the player
        if(this.getControllingPassenger() != null && this.isFlying())
        {
            float newPlaneRoll = this.planeRoll.get(this) - this.getSideInput() * 5F;
            newPlaneRoll = MathHelper.wrapDegrees(newPlaneRoll);
            this.planeRoll.set(this, newPlaneRoll);
        }
        else
        {
            this.planeRoll.set(this, this.planeRoll.get(this) * 0.9F);
        }

        VehicleProperties properties = this.getProperties();
        float enginePower = properties.getEnginePower();
        float friction = this.isFlying() ? 0F : SurfaceHelper.getFriction(this);
        float drag = 0.001F;
        float forwardForce = Math.max((this.propellerSpeed / 200F) - 0.4F, 0F);
        float liftForce = Math.min((float) (this.velocity.length() * 20) / this.getMinimumSpeedToFly(), 1.0F);
        float flapForce = this.isFlying() ? liftForce : (float) Math.floor(liftForce);
        this.flapAngle += ((this.getMaxFlapAngle() * this.getLift()) - this.flapAngle) * 0.15F;

        // Adds delta pitch and yaw to the plane based on the flaps and roll of the plane
        Vector3f direction = new Vector3f(Vector3d.directionFromRotation(this.flapAngle * flapForce * 0.05F, 0));
        direction.transform(Vector3f.ZP.rotationDegrees(this.planeRoll.get(this)));
        Vector3d deltaForward = new Vector3d(direction);
        this.xRot += CommonUtils.pitch(deltaForward);
        this.yRot -= CommonUtils.yaw(deltaForward);

        // Updates the accelerations of the plane with drag and friction applied
        Vector3d forward = Vector3d.directionFromRotation(this.getRotationVector());
        Vector3d acceleration = forward.scale(forwardForce).scale(enginePower).scale(0.05);
        if(friction > 0F)
        {
            Vector3d frictionForce = this.velocity.scale(-friction).scale(0.05);
            acceleration = acceleration.add(frictionForce);
        }
        Vector3d dragForce = this.velocity.scale(this.velocity.length()).scale(-drag).scale(0.05);
        acceleration = acceleration.add(dragForce);

        // Add gravity but is countered based on the lift force
        this.velocity = this.velocity.add(0, -0.05 * (1.0F - liftForce), 0);

        // Update the velocity based on the heading and acceleration
        this.velocity = CommonUtils.lerp(this.velocity, this.getForward().scale(acceleration.length()), 0.5F);

        // Updates the pitch and yaw based on the velocity
        if(this.isFlying() && this.velocity.multiply(1, 0, 1).length() > 0.01)
        {
            this.xRot = -CommonUtils.pitch(this.velocity);
            this.yRot = CommonUtils.yaw(this.velocity);
        }
        else
        {
            this.xRot = 0F;
        }

        // Finally adds velocity to the motion
        this.motion = this.motion.add(this.velocity);
    }

    protected void updateRotorSpeed()
    {
        if(this.canDrive() && this.getControllingPassenger() != null)
        {
            float enginePower = this.getProperties().getEnginePower();
            float maxRotorSpeed = this.getMaxRotorSpeed();
            if(this.propellerSpeed <= maxRotorSpeed)
            {
                this.propellerSpeed += this.getThrottle() > 0 ? Math.sqrt(enginePower) / 5F : 0.4F;
                if(this.propellerSpeed > maxRotorSpeed)
                {
                    this.propellerSpeed = maxRotorSpeed;
                }
            }
            else
            {
                this.propellerSpeed *= 0.99F;
            }
        }
        else
        {
            this.propellerSpeed *= 0.95F;
        }

        if(this.level.isClientSide())
        {
            this.propellerRotation += this.propellerSpeed;
        }
    }

    protected float getMaxRotorSpeed()
    {
        if(this.getThrottle() > 0)
        {
            //TODO implement pitch
            return 200F + this.getProperties().getEnginePower();
        }
        else if(this.isFlying())
        {
            if(this.getLift() < 0)
            {
                return 150F;
            }
            return 180F;
        }
        return 80F;
    }

    @Override
    public void onClientUpdate()
    {
        super.onClientUpdate();

        this.prevPropellerRotation = this.propellerRotation;

        LivingEntity entity = (LivingEntity) this.getControllingPassenger();
        if(entity != null && entity.equals(Minecraft.getInstance().player))
        {
            ClientPlayerEntity player = (ClientPlayerEntity) entity;
            this.setLift(VehicleHelper.getLift());
            this.setForwardInput(player.zza);
            this.setSideInput(player.xxa);
            PacketHandler.instance.sendToServer(new MessagePlaneInput(this.lift.getLocalValue(), player.zza, player.xxa));
        }
    }

    @Override
    protected void updateBodyRotations()
    {
        if(this.isFlying())
        {
            this.bodyRotationPitch = this.xRot;
            this.bodyRotationRoll = this.planeRoll.get(this);
        }
        else
        {
            this.bodyRotationPitch *= 0.75F;
            this.bodyRotationRoll *= 0.75F;
        }
        this.bodyRotationYaw = this.yRot;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT compound)
    {
        super.addAdditionalSaveData(compound);
        compound.putFloat("Lift", this.getLift());
        compound.putFloat("PlaneRoll", this.planeRoll.getLocalValue());
        CompoundNBT velocity = new CompoundNBT();
        velocity.putDouble("X", this.velocity.x);
        velocity.putDouble("Y", this.velocity.y);
        velocity.putDouble("Z", this.velocity.z);
        compound.put("Velocity", velocity);
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT compound)
    {
        super.readAdditionalSaveData(compound);
        if(compound.contains("Lift", Constants.NBT.TAG_FLOAT))
        {
            this.setLift(compound.getFloat("Lift"));
        }
        this.planeRoll.set(this, compound.getFloat("PlaneRoll"));
        CompoundNBT velocity = compound.getCompound("Velocity");
        this.velocity = new Vector3d(velocity.getDouble("X"), velocity.getDouble("Y"), velocity.getDouble("Z"));
    }

    @Override
    public void writeSpawnData(PacketBuffer buffer)
    {
        super.writeSpawnData(buffer);
        buffer.writeDouble(this.velocity.x);
        buffer.writeDouble(this.velocity.y);
        buffer.writeDouble(this.velocity.z);
    }

    @Override
    public void readSpawnData(PacketBuffer buffer)
    {
        super.readSpawnData(buffer);
        this.velocity = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public float getLift()
    {
        return this.lift.get(this);
    }

    public void setLift(float lift)
    {
        this.lift.set(this, lift);
    }

    public float getForwardInput()
    {
        return this.forwardInput.get(this);
    }

    public void setForwardInput(float input)
    {
        this.forwardInput.set(this, input);
    }

    public float getSideInput()
    {
        return this.sideInput.get(this);
    }

    public void setSideInput(float input)
    {
        this.sideInput.set(this, input);
    }

    public boolean isFlying()
    {
        return !this.onGround;
    }

    protected float getMinimumSpeedToFly()
    {
        return 16F;
    }

    public float getMaxFlapAngle()
    {
        return 45F;
    }

    /*
     * Overridden to prevent players from taking fall damage when landing a plane
     */
    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier)
    {
        return false;
    }

    @Override
    public boolean canChangeWheels()
    {
        return false;
    }

    @Override
    protected void updateEngineSound()
    {
        float normal = MathHelper.clamp(this.propellerSpeed / 200F, 0.0F, 1.25F) * 0.6F;
        //normal += (this.motion.scale(20).length() / this.getProperties().getEnginePower()) * 0.4F;
        this.enginePitch = this.getMinEnginePitch() + (this.getMaxEnginePitch() - this.getMinEnginePitch()) * MathHelper.clamp(normal, 0.0F, 1.0F);
        this.engineVolume = this.getControllingPassenger() != null && this.isEnginePowered() ? 0.2F + 0.8F * (this.propellerSpeed / 80F) : 0.001F;
    }

    @OnlyIn(Dist.CLIENT)
    public float getBladeRotation(float partialTicks)
    {
        return this.prevPropellerRotation + (this.propellerRotation - this.prevPropellerRotation) * partialTicks;
    }
}
